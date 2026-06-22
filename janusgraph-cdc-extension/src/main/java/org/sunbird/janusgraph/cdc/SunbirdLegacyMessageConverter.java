package org.sunbird.janusgraph.cdc;

import org.janusgraph.core.JanusGraphVertex;
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
    private static final String[] LABEL_KEYS = {"name", "lemma", "title", "gloss"};
    private static final String[] USER_ID_KEYS = {"lastUpdatedBy", "createdBy"};

    // Fields that should remain as JSON strings (loaded from config)
    private static final Set<String> STRING_ONLY_FIELDS = new HashSet<>();

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
        List<Map<String, Object>> addedRelations = new ArrayList<>();
        List<Map<String, Object>> removedRelations = new ArrayList<>();

        // Build property change map (ov/nv entries)
        // For DELETE: snapshot removed properties from ChangeState FIRST — the live
        // vertex has already been removed and vertex.properties() returns nothing.
        Map<String, Object> deletedProps = new HashMap<>();
        Map<String, Object> propertiesMap;

        if ("UPDATE".equals(operationType)) {
            // For UPDATE: build diffs from ChangeState and track actual changes.
            // ChangeState iterables may be single-use, so this MUST be the first
            // (and only) time they are consumed.
            Set<String> changedKeys = new HashSet<>();
            propertiesMap = buildUpdateProperties(vertex, changeState, changedKeys);
            collectRelationChanges(vertex, changeState, addedRelations, removedRelations);

            // If there are no actual property or relation changes, skip this event
            if (changedKeys.isEmpty() && addedRelations.isEmpty() && removedRelations.isEmpty()) {
                logger.debug("No actual changes detected for vertex {}, skipping UPDATE event", vertex.id());
                return null;
            }
        } else {
            if ("DELETE".equals(operationType)) {
                deletedProps = buildRemovedSnapshot(vertex, changeState);
            }
            propertiesMap = buildPropertyChanges(vertex, changeState, operationType, deletedProps);
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

        // Derived Fields:
        // - DELETE: read from removed-property snapshot (vertex already gone in this TX)
        // - UPDATE: read from propertiesMap (transaction log) first, fall back to vertex.
        //   The vertex may have been deleted by a subsequent transaction before CDC
        //   processes this one (e.g. publish job: updateProcessingNode → deleteNode).
        // - CREATE: read from live vertex (just created, always exists)
        boolean isDelete = "DELETE".equals(operationType);
        boolean isUpdate = "UPDATE".equals(operationType);
        map.put("createdOn", DATE_FORMATTER.format(Instant.now()));
        map.put("channel", isDelete ? getFromSnapshot(deletedProps, "channel", "all")
                : isUpdate ? getFromPropertiesOrVertex(propertiesMap, vertex, "channel", "all")
                : getVertexProperty(vertex, "channel", "all"));
        map.put("label", isDelete ? getLabelFromSnapshot(deletedProps, vertex)
                : isUpdate ? getLabelFromPropertiesOrVertex(propertiesMap, vertex)
                : getLabel(vertex));
        map.put("nodeType", isDelete ? getFromSnapshot(deletedProps, "IL_SYS_NODE_TYPE", "DATA_NODE")
                : isUpdate ? getFromPropertiesOrVertex(propertiesMap, vertex, "IL_SYS_NODE_TYPE", "DATA_NODE")
                : getVertexProperty(vertex, "IL_SYS_NODE_TYPE", "DATA_NODE"));
        String objectType = isDelete ? getFromSnapshot(deletedProps, "IL_FUNC_OBJECT_TYPE", vertex.label())
                : isUpdate ? getFromPropertiesOrVertex(propertiesMap, vertex, "IL_FUNC_OBJECT_TYPE", vertex.label())
                : getVertexProperty(vertex, "IL_FUNC_OBJECT_TYPE", vertex.label());
        // Filter out internal JanusGraph vertices that have no IL_FUNC_OBJECT_TYPE set
        // (their label falls back to TinkerPop's default "vertex")
        if ("vertex".equalsIgnoreCase(objectType)) {
            logger.debug("Skipping event for vertex {} with objectType='vertex' (no IL_FUNC_OBJECT_TYPE set)",
                    vertex.id());
            return null;
        }
        map.put("objectType", objectType);
        map.put("nodeUniqueId", isDelete ? getFromSnapshot(deletedProps, "IL_UNIQUE_ID", vertex.id().toString())
                : isUpdate ? getFromPropertiesOrVertex(propertiesMap, vertex, "IL_UNIQUE_ID", vertex.id().toString())
                : getVertexProperty(vertex, "IL_UNIQUE_ID", vertex.id().toString()));
        map.put("userId", isDelete ? getUserIdFromSnapshot(deletedProps, vertex)
                : isUpdate ? getUserIdFromPropertiesOrVertex(propertiesMap, vertex)
                : getUserId(vertex));

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

    /**
     * Reads a single property value from the vertex, returning a default if not found.
     */
    private String getVertexProperty(JanusGraphVertex vertex, String key, String defaultValue) {
        try {
            Iterator<org.apache.tinkerpop.gremlin.structure.VertexProperty<Object>> it = vertex.properties(key);
            if (it.hasNext()) {
                Object val = it.next().value();
                return val != null ? val.toString() : defaultValue;
            }
        } catch (Exception e) {
            logger.warn("Error reading property '{}' from vertex {}", key, vertex.id(), e);
        }
        return defaultValue;
    }

    private String getLabel(JanusGraphVertex vertex) {
        // Legacy: name -> lemma -> title -> gloss
        for (String key : LABEL_KEYS) {
            String val = getVertexProperty(vertex, key, null);
            if (val != null) return val;
        }
        return vertex.label();
    }

    private String getUserId(JanusGraphVertex vertex) {
        for (String key : USER_ID_KEYS) {
            String val = getVertexProperty(vertex, key, null);
            if (val != null) return val;
        }
        return "ANONYMOUS";
    }

    /**
     * Builds a snapshot of all properties that were removed as part of a DELETE
     * transaction, using ChangeState. Must be called before vertex.properties()
     * becomes unavailable (i.e., before buildPropertyChanges for DELETE).
     */
    private Map<String, Object> buildRemovedSnapshot(JanusGraphVertex vertex, ChangeState changeState) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        try {
            changeState.getProperties(vertex, Change.REMOVED).iterator().forEachRemaining(p ->
                    snapshot.put(p.key(), processValue(p.key(), p.value())));
        } catch (Exception e) {
            logger.warn("Could not read removed properties from ChangeState for vertex {}", vertex.id(), e);
        }
        return snapshot;
    }

    private String getFromSnapshot(Map<String, Object> snapshot, String key, String defaultValue) {
        Object val = snapshot.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private String getLabelFromSnapshot(Map<String, Object> snapshot, JanusGraphVertex vertex) {
        for (String key : LABEL_KEYS) {
            Object val = snapshot.get(key);
            if (val != null) return val.toString();
        }
        return vertex.label();
    }

    private String getUserIdFromSnapshot(Map<String, Object> snapshot, JanusGraphVertex vertex) {
        for (String key : USER_ID_KEYS) {
            Object val = snapshot.get(key);
            if (val != null) return val.toString();
        }
        return "ANONYMOUS";
    }

    // --- Helpers: read derived fields from propertiesMap (transaction log) first,
    //     fall back to vertex. Handles race condition where vertex is deleted by a
    //     subsequent transaction before CDC processes this UPDATE event. ---

    @SuppressWarnings("unchecked")
    private String getFromPropertiesOrVertex(Map<String, Object> propertiesMap, JanusGraphVertex vertex,
            String key, String defaultValue) {
        Object entry = propertiesMap.get(key);
        if (entry instanceof Map) {
            Object nv = ((Map<String, Object>) entry).get("nv");
            if (nv != null) return nv.toString();
        }
        return getVertexProperty(vertex, key, defaultValue);
    }

    private String getLabelFromPropertiesOrVertex(Map<String, Object> propertiesMap, JanusGraphVertex vertex) {
        for (String key : LABEL_KEYS) {
            String val = getFromPropertiesOrVertex(propertiesMap, vertex, key, null);
            if (val != null) return val;
        }
        return vertex.label();
    }

    private String getUserIdFromPropertiesOrVertex(Map<String, Object> propertiesMap, JanusGraphVertex vertex) {
        for (String key : USER_ID_KEYS) {
            String val = getFromPropertiesOrVertex(propertiesMap, vertex, key, null);
            if (val != null) return val;
        }
        return "ANONYMOUS";
    }

    // --- Refactored property change helpers ---

    /**
     * Creates a single {ov, nv} property entry.
     */
    private Map<String, Object> createPropertyEntry(Object ov, Object nv) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("ov", ov);
        entry.put("nv", nv);
        return entry;
    }

    /**
     * Builds the property change map for CREATE and DELETE operations.
     * For CREATE: ov=null, nv=current value.
     * For DELETE: ov=current value, nv=null.
     */
    private Map<String, Object> buildPropertyChanges(JanusGraphVertex vertex, ChangeState changeState,
            String operationType, Map<String, Object> deletedProps) {
        Map<String, Object> propertiesMap = new LinkedHashMap<>();

        switch (operationType) {
            case "CREATE":
                buildCreateProperties(vertex, propertiesMap);
                break;
            case "DELETE":
                buildDeleteProperties(deletedProps, propertiesMap);
                break;
            default:
                logger.warn("Unknown operationType: {}", operationType);
        }
        return propertiesMap;
    }

    private void buildCreateProperties(JanusGraphVertex vertex, Map<String, Object> propertiesMap) {
        try {
            vertex.properties().forEachRemaining(p ->
                    propertiesMap.put(p.key(), createPropertyEntry(null, processValue(p.key(), p.value()))));
        } catch (Exception e) {
            logger.warn("Error reading properties for created vertex {}", vertex.id(), e);
        }
    }

    /**
     * Builds property changes for UPDATE operations.
     * Only includes properties that actually changed (from ChangeState ADDED/REMOVED).
     * Populates changedKeys with keys that had actual changes.
     *
     * @param changedKeys populated with keys that had actual ADDED/REMOVED changes
     * @return the properties map with {ov, nv} entries
     */
    private Map<String, Object> buildUpdateProperties(JanusGraphVertex vertex, ChangeState changeState,
            Set<String> changedKeys) {
        Map<String, Object> propertiesMap = new LinkedHashMap<>();

        // 1. Collect all REMOVED (old) values into a lookup map
        //    Note: JanusGraph's user transaction log may NOT provide REMOVED for SINGLE
        //    cardinality property updates — in that case ov will be null.
        Map<String, Object> oldValues = new LinkedHashMap<>();
        try {
            changeState.getProperties(vertex, Change.REMOVED).iterator().forEachRemaining(p ->
                    oldValues.put(p.key(), processValue(p.key(), p.value())));
        } catch (Exception e) {
            logger.warn("Error collecting removed properties for vertex {}", vertex.id(), e);
        }

        // 2. Process ADDED properties (new values), pairing with old values
        try {
            changeState.getProperties(vertex, Change.ADDED).iterator().forEachRemaining(p -> {
                String key = p.key();
                changedKeys.add(key);
                Object nv = processValue(key, p.value());
                Object ov = oldValues.remove(key);
                propertiesMap.put(key, createPropertyEntry(ov, nv));
            });
        } catch (Exception e) {
            logger.warn("Error processing added properties for vertex {}", vertex.id(), e);
        }

        // 3. Remaining old values = properties that were deleted (REMOVED but not re-ADDED)
        oldValues.forEach((key, ov) -> {
            changedKeys.add(key);
            propertiesMap.put(key, createPropertyEntry(ov, null));
        });

        return propertiesMap;
    }

    private void buildDeleteProperties(Map<String, Object> deletedProps, Map<String, Object> propertiesMap) {
        // deletedProps was built from changeState.getProperties(vertex, Change.REMOVED)
        // before the vertex was removed, so it contains all pre-deletion property values.
        deletedProps.forEach((key, value) ->
                propertiesMap.put(key, createPropertyEntry(value, null)));
    }

    /**
     * Collects added/removed edge (relation) changes for a vertex.
     */
    private void collectRelationChanges(JanusGraphVertex vertex, ChangeState changeState,
            List<Map<String, Object>> addedRelations, List<Map<String, Object>> removedRelations) {
        try {
            for (Direction dir : new Direction[]{Direction.OUT, Direction.IN}) {
                changeState.getEdges(vertex, Change.ADDED, dir).iterator().forEachRemaining(edge ->
                        addedRelations.add(processRelation(edge, dir)));
                changeState.getEdges(vertex, Change.REMOVED, dir).iterator().forEachRemaining(edge ->
                        removedRelations.add(processRelation(edge, dir)));
            }
            logger.info("Vertex {} relation changes — added: {}, removed: {}",
                    vertex.id(), addedRelations.size(), removedRelations.size());
        } catch (Exception e) {
            logger.warn("Error processing relation changes for vertex {}", vertex.id(), e);
        }
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
