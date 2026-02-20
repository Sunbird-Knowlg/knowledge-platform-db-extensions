package org.sunbird.janusgraph.cdc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class GraphLogProcessorTest {

    private Object processor;
    private Method shouldProcessEventMethod;
    private Map<String, Long> cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        // Reflectively create instance
        Constructor<GraphLogProcessor> constructor = GraphLogProcessor.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        processor = constructor.newInstance();

        // Access private method
        shouldProcessEventMethod = GraphLogProcessor.class.getDeclaredMethod("shouldProcessEvent", Map.class);
        shouldProcessEventMethod.setAccessible(true);

        // Access private cache field to verify state if needed
        Field cacheField = GraphLogProcessor.class.getDeclaredField("lastUpdatedCache");
        cacheField.setAccessible(true);
        cache = (Map<String, Long>) cacheField.get(processor);
    }

    private boolean invokeShouldProcessEvent(Map<String, Object> event) throws Exception {
        return (boolean) shouldProcessEventMethod.invoke(processor, event);
    }

    @Test
    public void testEventOrdering() throws Exception {
        String nodeId = "do_123";

        // 1. Process initial event (T1)
        long t1 = 1000L;
        Map<String, Object> event1 = createEvent(nodeId, t1);
        boolean result1 = invokeShouldProcessEvent(event1);
        assertTrue(result1, "Should process fresh event");
        assertEquals(t1, cache.get(nodeId));

        // 2. Process newer event (T2 > T1)
        long t2 = 2000L;
        Map<String, Object> event2 = createEvent(nodeId, t2);
        boolean result2 = invokeShouldProcessEvent(event2);
        assertTrue(result2, "Should process newer event");
        assertEquals(t2, cache.get(nodeId));

        // 3. Process older event (T1 < T2)
        Map<String, Object> eventOld = createEvent(nodeId, t1);
        boolean resultOld = invokeShouldProcessEvent(eventOld);
        assertFalse(resultOld, "Should ignore older event");
        assertEquals(t2, cache.get(nodeId)); // Cache should still have T2

        // 4. Process same timestamp event (T2 == T2)
        Map<String, Object> eventSame = createEvent(nodeId, t2);
        boolean resultSame = invokeShouldProcessEvent(eventSame);
        assertFalse(resultSame, "Should ignore duplicate/same timestamp event");
        assertEquals(t2, cache.get(nodeId));
    }

    @Test
    public void testTimestampParsing_ISOString() throws Exception {
        String nodeId = "do_iso";
        String isoTime = "2026-02-11T05:34:06.476+0000";
        // Epoch for this time check?
        // 2026-02-11T05:34:06.476+0000 is parsed using
        // DateTimeFormatter.ISO_OFFSET_DATE_TIME
        // Let's manually parse it to assert
        long expected = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                .parse(isoTime, Instant::from)
                .toEpochMilli();

        Map<String, Object> event = createEvent(nodeId, isoTime);
        boolean result = invokeShouldProcessEvent(event);
        assertTrue(result);
        assertEquals(expected, cache.get(nodeId));
    }

    @Test
    public void testNestedStructure() throws Exception {
        // Simulate TelemetryMessageConverter structure: transactionData -> properties
        // -> lastUpdatedOn -> nv
        String nodeId = "do_nested";
        Long time = 5000L;

        Map<String, Object> nvMap = new HashMap<>();
        nvMap.put("nv", time);

        Map<String, Object> props = new HashMap<>();
        props.put("lastUpdatedOn", nvMap);
        props.put("status", "Draft");

        Map<String, Object> txData = new HashMap<>();
        txData.put("properties", props);

        Map<String, Object> event = new HashMap<>();
        event.put("nodeUniqueId", nodeId);
        event.put("transactionData", txData);

        boolean result = invokeShouldProcessEvent(event);
        assertTrue(result);
        assertEquals(time, cache.get(nodeId));
    }

    @Test
    public void testFlatStructure() throws Exception {
        // Simulate SimpleMessageConverter
        String nodeId = "do_flat";
        Long time = 6000L;

        Map<String, Object> props = new HashMap<>();
        props.put("lastUpdatedOn", time);

        Map<String, Object> event = new HashMap<>();
        event.put("nodeUniqueId", nodeId);
        event.put("properties", props);

        boolean result = invokeShouldProcessEvent(event);
        assertTrue(result);
        assertEquals(time, cache.get(nodeId));
    }

    private Map<String, Object> createEvent(String nodeId, Object lastUpdatedOn) {
        Map<String, Object> event = new HashMap<>();
        event.put("nodeUniqueId", nodeId);

        // Structure matches logic in getLastUpdatedOn

        // Create nested structure as primary test target
        Map<String, Object> nvMap = new HashMap<>();
        nvMap.put("nv", lastUpdatedOn);

        Map<String, Object> innerProps = new HashMap<>();
        innerProps.put("lastUpdatedOn", nvMap);

        Map<String, Object> txData = new HashMap<>();
        txData.put("properties", innerProps);

        event.put("transactionData", txData);

        return event;
    }
}
