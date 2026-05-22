package io.github.lingjiuu.event;

import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.transcript.TranscriptRecorder;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventManagerTest extends TestCase {

    public void testEventsAreStampedAndDispatchedOnOneUiThread() {
        EventManager events = new EventManager();
        List<UiEvent> received = new CopyOnWriteArrayList<>();
        List<String> threadNames = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(EventManager.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            events.subscribe(event -> {
                throw new RuntimeException("sink failed");
            });
            events.subscribe(event -> {
                received.add(event);
                threadNames.add(Thread.currentThread().getName());
            });

            events.emit(UiEvent.builder().type(UiEventType.TURN_STARTED).build());
            events.emit(UiEvent.builder().type(UiEventType.TURN_COMPLETED).build());
            events.emit(UiEvent.builder().type(UiEventType.TOOL_EXECUTION_UPDATE).build());
            events.flush();

            assertEquals(3, received.size());
            assertEquals(Long.valueOf(1), received.get(0).getSequence());
            assertEquals(Long.valueOf(2), received.get(1).getSequence());
            assertEquals(Long.valueOf(3), received.get(2).getSequence());
            assertTrue(threadNames.stream().allMatch("aether-ui-events"::equals));
        } finally {
            logger.setLevel(previousLevel);
            events.close();
        }
    }

    public void testSequencesFollowDispatchOrderForConcurrentEmitters() throws Exception {
        EventManager events = new EventManager();
        List<UiEvent> received = new CopyOnWriteArrayList<>();
        int eventCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            events.subscribe(received::add);
            for (int i = 0; i < eventCount; i++) {
                executor.submit(() -> {
                    start.await();
                    events.emit(UiEvent.builder().type(UiEventType.TOOL_EXECUTION_UPDATE).build());
                    return null;
                });
            }

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            events.flush();

            assertEquals(eventCount, received.size());
            for (int i = 0; i < received.size(); i++) {
                assertEquals(Long.valueOf(i + 1L), received.get(i).getSequence());
            }
        } finally {
            executor.shutdownNow();
            events.close();
        }
    }

    public void testEventsArePersistedAfterStamping() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-event-test"));
        EventManager events = new EventManager(new TranscriptRecorder(store, sessionId), List.of(), 0);
        try {
            var turnContext = new io.github.lingjiuu.agent.turn.TurnContext(
                    io.github.lingjiuu.agent.turn.TurnId.create(),
                    sessionId,
                    1,
                    java.nio.file.Path.of(".")
            );
            events.emit(UiEvents.turnStarted(turnContext));
            events.emit(UiEvents.turnCompleted(turnContext));
            events.flush();

            var records = store.read(sessionId);
            assertEquals(2, records.size());
            assertTrue(records.get(0).getItem() instanceof EventTranscriptItem);
            UiEvent first = ((EventTranscriptItem) records.get(0).getItem()).getEvent();
            assertEquals(UiEventType.TURN_STARTED, first.getType());
            assertEquals(Long.valueOf(1), first.getSequence());
            assertNotNull(first.getTimestampMs());
            assertTrue(records.get(1).getItem() instanceof EventTranscriptItem);
            UiEvent second = ((EventTranscriptItem) records.get(1).getItem()).getEvent();
            assertEquals(Long.valueOf(2), second.getSequence());
        } finally {
            events.close();
        }
    }

    public void testOnlyDurableEventsArePersisted() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-event-test"));
        EventManager events = new EventManager(new TranscriptRecorder(store, sessionId), List.of(), 0);
        List<UiEvent> received = new CopyOnWriteArrayList<>();
        try {
            events.subscribe(received::add);
            events.emit(UiEvent.builder().type(UiEventType.TURN_STARTED).sessionId(sessionId).turn(1).build());
            events.emit(UiEvent.builder().type(UiEventType.ASSISTANT_TEXT_DELTA).sessionId(sessionId).turn(1).build());
            events.emit(UiEvent.builder().type(UiEventType.ITEM_COMPLETED).sessionId(sessionId).turn(1).build());
            events.emit(UiEvent.builder().type(UiEventType.TOOL_EXECUTION_UPDATE).sessionId(sessionId).turn(1).build());
            events.emit(UiEvent.builder().type(UiEventType.TOOL_RESULT).sessionId(sessionId).turn(1).build());
            events.flush();

            assertEquals(5, received.size());

            var records = store.read(sessionId);
            assertEquals(3, records.size());
            assertEquals(UiEventType.TURN_STARTED, persistedEvent(records, 0).getType());
            assertEquals(Long.valueOf(1), persistedEvent(records, 0).getSequence());
            assertEquals(UiEventType.ITEM_COMPLETED, persistedEvent(records, 1).getType());
            assertEquals(Long.valueOf(3), persistedEvent(records, 1).getSequence());
            assertEquals(UiEventType.TOOL_RESULT, persistedEvent(records, 2).getType());
            assertEquals(Long.valueOf(5), persistedEvent(records, 2).getSequence());
        } finally {
            events.close();
        }
    }

    public void testReplayTimelineDoesNotRestampOrPersistEvents() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-event-test"));
        UiEvent restored = UiEvent.builder()
                .type(UiEventType.TURN_COMPLETED)
                .sessionId(sessionId)
                .turn(1)
                .sequence(42L)
                .timestampMs(100L)
                .build();
        EventManager events = new EventManager(new TranscriptRecorder(store, sessionId), List.of(restored), 42);
        List<UiEvent> received = new CopyOnWriteArrayList<>();
        try {
            events.replayTimeline(received::add);

            assertEquals(1, received.size());
            assertEquals(Long.valueOf(42), received.getFirst().getSequence());
            assertEquals(Long.valueOf(100), received.getFirst().getTimestampMs());
            assertEquals(0, store.read(sessionId).size());
        } finally {
            events.close();
        }
    }

    private UiEvent persistedEvent(List<io.github.lingjiuu.transcript.TranscriptRecord> records, int index) {
        assertTrue(records.get(index).getItem() instanceof EventTranscriptItem);
        return ((EventTranscriptItem) records.get(index).getItem()).getEvent();
    }
}
