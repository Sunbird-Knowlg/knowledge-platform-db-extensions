package org.sunbird.janusgraph.cdc;

import org.janusgraph.core.JanusGraphVertex;
import org.janusgraph.core.JanusGraphVertexProperty;
import org.janusgraph.core.log.Change;
import org.janusgraph.core.log.ChangeState;
import org.janusgraph.core.log.TransactionId;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SunbirdLegacyMessageConverter implements MessageConverter {

    private static final Logger logger = LoggerFactory.getLogger(SunbirdLegacyMessageConverter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .withZone(ZoneId.systemDefault());

    // Fields that should remain as JSON strings (loaded from config)
    private static final Set<String> STRING_ONLY_FIELDS = new HashSet<>();

    // Fields that must be included in the event if present on the vertex, even if
    // not changed
    private static final Set<String> REQUIRED_FIELDS = new HashSet<>(Arrays.asList(
            "IL_UNIQUE_ID", "IL_FUNC_OBJECT_TYPE", "IL_SYS_NODE_TYPE", "status", "pkgVersion",
            "lastUpdatedOn", "createdOn", "channel",
            "name", "lemma", "title", "gloss", // For label
            "lastUpdatedBy", "createdBy" // For userId
    ));

    static {
        // Load configuration from cdc-converter.conf
        try (InputStream input = SunbirdLegacyMessageConverter.class.getClassLoader()
                .getResourceAsStream("cdc-converter.conf")) {
            if (input != null) {
                Properties prop = new Properties();
                prop.load(input);
                String fields = prop.getProperty("string.only.fields", "");
                if (!fields.isEmpty()) {
                    String[] fieldArray = fields.split(",");
                    for (String field : fieldArray) {
                        STRING_ONLY_FIELDS.add(field.trim());
                    }
                    logger.info("Loaded string-only fields: {}", STRING_ONLY_FIELDS);
                }
            } else {
                logger.warn("cdc-converter.conf not found, no string-only fields configured");
            }
        } catch (Exception e) {
            logger.error("Error loading cdc-converter.conf", e);
        }
    }

    @Override
    public Map<String, Object> convert(JanusGraphVertex vertex, ChangeState changeState, String operationType,
            TransactionId txId) {
        Map<String, Object> map = new HashMap<>();
        long ets = System.currentTimeMillis();
        String mid = UUID.randomUUID().toString();

        // Basic fields
        map.put("ets", ets);
        map.put("mid", mid);
        map.put("requestId", null); // Not available in CDC
        try {
            map.put("nodeGraphId", Long.parseLong(vertex.id().toString())); // Using vertex ID as numeric graph ID
        } catch (NumberFormatException e) {
            map.put("nodeGraphId", 0L); // Default if non-numeric
        }
        map.put("graphId", "domain"); // Default

        // Operation Type
        map.put("operationType", operationType);

        // Transaction Data
        Map<String, Object> transactionData = new HashMap<>();
        Map<String, Object> propertiesMap = new HashMap<>();
        List<Map<String, Object>> addedRelations = new ArrayList<>();
        List<Map<String, Object>> removedRelations = new ArrayList<>();

        // Populate Properties (nv/ov)
        // For CREATE: ov is null, nv is value
        // For DELETE: ov is confirmed value, nv is null
        // For UPDATE: logic required to find diff

        if ("CREATE".equals(operationType)) {
            vertex.properties().forEachRemaining(p -> {
                Map<String, Object> valMap = new HashMap<>();
                valMap.put("ov", null);
                valMap.put("nv", processValue(p.key(), p.value()));
                propertiesMap.put(p.key(), valMap);
            });
        } else if ("UPDATE".equals(operationType)) {
            Set<String> processedKeys = new HashSet<>();

            // 1. Process ADDED properties (new or updated values)
            try {
                changeState.getProperties(vertex, Change.ADDED).iterator().forEachRemaining(p -> {
                    String key = p.key();
                    processedKeys.add(key);
                    Map<String, Object> valMap = new HashMap<>();
                    valMap.put("nv", processValue(key, p.value()));

                    Object ov = null;
                    try {
                        // Fetch the removed property with the same key which represents the old value
                        Iterator<JanusGraphVertexProperty> removedProps = changeState
                                .getProperties(vertex, Change.REMOVED, key).iterator();
                        if (removedProps.hasNext()) {
                            ov = processValue(key, removedProps.next().value());
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    valMap.put("ov", ov);
                    propertiesMap.put(key, valMap);
                });
            } catch (Exception e) {
                logger.warn("Error processing added properties for vertex {}", vertex.id(), e);
            }

            // 2. Process REMOVED properties (deleted properties not in ADDED)
            try {
                changeState.getProperties(vertex, Change.REMOVED).iterator().forEachRemaining(p -> {
                    String key = p.key();
                    if (!processedKeys.contains(key)) {
                        processedKeys.add(key); // Mark as processed to avoid overwrite
                        Map<String, Object> valMap = new HashMap<>();
                        valMap.put("ov", processValue(key, p.value()));
                        valMap.put("nv", null);
                        propertiesMap.put(key, valMap);
                    }
                });
            } catch (Exception e) {
                logger.warn("Error processing removed properties for vertex {}", vertex.id(), e);
            }

            // 3. Add REQUIRED current vertex properties (partial snapshot)
            // This ensures uniqueness and identity fields are always present
            try {
                vertex.properties().forEachRemaining(p -> {
                    String key = p.key();
                    if (REQUIRED_FIELDS.contains(key) && !processedKeys.contains(key)) {
                        Map<String, Object> valMap = new HashMap<>();
                        valMap.put("nv", processValue(key, p.value()));
                        valMap.put("ov", null); // Unchanged property
                        propertiesMap.put(key, valMap);
                    }
                });
            } catch (Exception e) {
                logger.warn("Error processing required properties for vertex {}", vertex.id(), e);
            }

            // 4. Added Relations
            try {
                // OUT Edges
                changeState.getEdges(vertex, Change.ADDED, Direction.OUT).iterator().forEachRemaining(edge -> {
                    addedRelations.add(processRelation(edge, Direction.OUT));
                });

                // IN Edges
                changeState.getEdges(vertex, Change.ADDED, Direction.IN).iterator().forEachRemaining(edge -> {
                    addedRelations.add(processRelation(edge, Direction.IN));
                });
            } catch (Exception e) {
                logger.warn("Error processing added relations for vertex {}", vertex.id(), e);
            }

            // 5. Removed Relations
            try {
                // OUT Edges
                changeState.getEdges(vertex, Change.REMOVED, Direction.OUT).iterator().forEachRemaining(edge -> {
                    removedRelations.add(processRelation(edge, Direction.OUT));
                });

                // IN Edges
                changeState.getEdges(vertex, Change.REMOVED, Direction.IN).iterator().forEachRemaining(edge -> {
                    removedRelations.add(processRelation(edge, Direction.IN));
                });
            } catch (Exception e) {
                logger.warn("Error processing removed relations for vertex {}", vertex.id(), e);
            }
        } else if ("DELETE".equals(operationType)) {
            // In DELETE, changeState might provide REMOVED properties, or we access what we
            // can
            // Note: Vertex might be empty if already removed, but ChangeState should have
            // it.
            // However, for DELETE, we rely on what's available.
            // The passed 'vertex' is from getVertices(Change.REMOVED).
            // We can check removed properties if needed, or assume current state is 'ov'.
            // JanusGraph might not provide properties on a removed vertex handle easily.
            // We'll attempt to iterate properties if they exist in memory trace.

            // Strategy: Iterate properties if available. if not, we can't emit much.
            try {
                vertex.properties().forEachRemaining(p -> {
                    Map<String, Object> valMap = new HashMap<>();
                    valMap.put("ov", processValue(p.key(), p.value()));
                    valMap.put("nv", null);
                    propertiesMap.put(p.key(), valMap);
                });
            } catch (Exception e) {
                logger.warn("Could not read properties for deleted vertex {}", vertex.id());
            }
        }

        // Add properties to transactionData
        transactionData.put("properties", propertiesMap);

        // Placeholder for Relations/Tags as per legacy format (empty for now)
        transactionData.put("addedTags", new ArrayList<>());
        transactionData.put("removedTags", new ArrayList<>());
        // Populate Relations
        transactionData.put("addedRelations", addedRelations);
        transactionData.put("removedRelations", removedRelations);

        map.put("transactionData", transactionData);

        // Derived Fields from props
        Map<String, Object> flatProps = flattenProperties(propertiesMap);

        map.put("createdOn", DATE_FORMATTER.format(Instant.now())); // Current time as we process
        map.put("channel", flatProps.getOrDefault("channel", "all"));

        // Label logic
        map.put("label", getLabel(vertex, flatProps));

        // Node Type
        map.put("nodeType", flatProps.getOrDefault("IL_SYS_NODE_TYPE", "DATA_NODE"));

        // Object Type
        map.put("objectType", flatProps.getOrDefault("IL_FUNC_OBJECT_TYPE", vertex.label()));

        // Unique ID
        map.put("nodeUniqueId", flatProps.getOrDefault("IL_UNIQUE_ID", vertex.id().toString()));

        // User ID
        map.put("userId", getUserId(flatProps));

        // Filter unnecessary events for ROOT node
        // Only if there are NO added/removed relations
        if ("root".equalsIgnoreCase(String.valueOf(map.get("nodeUniqueId"))) ||
                "ROOT".equalsIgnoreCase(String.valueOf(map.get("objectType")))) {

            boolean hasAdded = !addedRelations.isEmpty();
            boolean hasRemoved = !removedRelations.isEmpty();

            if (!hasAdded && !hasRemoved) {
                return null;
            }
        }

        return map;
    }

    private Map<String, Object> processRelation(Edge edge, Direction direction) {
        Map<String, Object> relation = new HashMap<>();
        try {
            Vertex otherVertex = (direction == Direction.OUT) ? edge.inVertex() : edge.outVertex();

            relation.put("dir", direction.name());
            relation.put("rel", edge.label());

            // Fetch properties of the other vertex
            // We need to iterate properties to find ID, Label, Type
            // Note: JanusGraph might require property access

            String id = null;
            String type = null;
            String label = null;

            try {
                // Try to get IL_UNIQUE_ID
                // For vertices in a transaction, we can access properties
                Iterator<org.apache.tinkerpop.gremlin.structure.VertexProperty<Object>> props = otherVertex
                        .properties("IL_UNIQUE_ID", "IL_FUNC_OBJECT_TYPE", "name", "title", "lemma", "gloss");
                while (props.hasNext()) {
                    org.apache.tinkerpop.gremlin.structure.VertexProperty<Object> p = props.next();
                    if ("IL_UNIQUE_ID".equals(p.key()))
                        id = (String) p.value();
                    if ("IL_FUNC_OBJECT_TYPE".equals(p.key()))
                        type = (String) p.value();
                    if (label == null && ("name".equals(p.key()) || "title".equals(p.key()) || "lemma".equals(p.key())
                            || "gloss".equals(p.key()))) {
                        label = (String) p.value();
                    }
                }
            } catch (Exception e) {
                logger.warn("Error fetching properties for relation vertex {}", otherVertex.id(), e);
            }

            if (id == null)
                id = otherVertex.id().toString(); // Fallback to graph ID
            if (type == null)
                type = otherVertex.label(); // Fallback to vertex label
            if (label == null)
                label = otherVertex.label(); // Fallback

            relation.put("id", id);
            relation.put("type", type);
            relation.put("label", label);

            // Extract edge metadata
            Map<String, Object> relMetadata = new HashMap<>();
            edge.properties().forEachRemaining(p -> {
                relMetadata.put(p.key(), p.value());
            });
            relation.put("relMetadata", relMetadata);

        } catch (Exception e) {
            logger.warn("Error processing relation", e);
        }
        return relation;
    }

    private Map<String, Object> flattenProperties(Map<String, Object> propertiesMap) {
        Map<String, Object> flat = new HashMap<>();
        for (Map.Entry<String, Object> entry : propertiesMap.entrySet()) {
            Map<String, Object> valMap = (Map<String, Object>) entry.getValue();
            // multiple logic: prefer nv, else ov
            Object val = valMap.get("nv");
            if (val == null)
                val = valMap.get("ov");
            if (val != null)
                flat.put(entry.getKey(), val);
        }
        return flat;
    }

    private String getLabel(JanusGraphVertex vertex, Map<String, Object> props) {
        // Legacy: name -> lemma -> title -> gloss
        if (props.containsKey("name"))
            return (String) props.get("name");
        if (props.containsKey("lemma"))
            return (String) props.get("lemma");
        if (props.containsKey("title"))
            return (String) props.get("title");
        if (props.containsKey("gloss"))
            return (String) props.get("gloss");
        return vertex.label();
    }

    private String getUserId(Map<String, Object> props) {
        if (props.containsKey("lastUpdatedBy"))
            return (String) props.get("lastUpdatedBy");
        if (props.containsKey("createdBy"))
            return (String) props.get("createdBy");
        return "ANONYMOUS";
    }

    /**
     * Process property value based on configuration.
     * Parse JSON strings into objects/arrays for ALL fields EXCEPT those in
     * STRING_ONLY_FIELDS.
     */
    private Object processValue(String key, Object value) {
        // If field is in STRING_ONLY_FIELDS, keep it as string (don't parse)
        if (STRING_ONLY_FIELDS.contains(key)) {
            return value;
        }

        // For all other fields, try to parse JSON strings
        if (value instanceof String) {
            String str = (String) value;
            try {
                if ((str.startsWith("{") && str.endsWith("}")) || (str.startsWith("[") && str.endsWith("]"))) {
                    return mapper.readValue(str, Object.class);
                }
            } catch (Exception e) {
                // Return original string if parsing fails
            }
        }
        return value;
    }
}
