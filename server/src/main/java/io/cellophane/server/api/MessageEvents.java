package io.cellophane.server.api;

import io.cellophane.server.message.Message;
import io.cellophane.server.message.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans inbox changes out to every open server-sent-events stream. */
public final class MessageEvents implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessageEvents.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            remove.run();
        }
        return emitter;
    }

    public int subscribers() {
        return emitters.size();
    }

    @Override
    public void onMessage(Message message) {
        broadcast(SseEmitter.event().name("message").data(MessageSummary.of(message), MediaType.APPLICATION_JSON));
    }

    @Override
    public void onCleared() {
        broadcast(SseEmitter.event().name("cleared").data("{}", MediaType.APPLICATION_JSON));
    }

    /** Keeps idle connections alive through proxies that close quiet streams. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        broadcast(SseEmitter.event().comment("ping"));
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                log.debug("dropping SSE subscriber: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
