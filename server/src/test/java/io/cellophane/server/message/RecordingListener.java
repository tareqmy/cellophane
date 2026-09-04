package io.cellophane.server.message;

import java.util.ArrayList;
import java.util.List;

/** Test double that remembers every inbox event in order. */
final class RecordingListener implements MessageListener {

    final List<Message> created = new ArrayList<>();
    final List<Message> updated = new ArrayList<>();
    int cleared;

    @Override
    public void onMessage(Message message) {
        created.add(message);
    }

    @Override
    public void onUpdated(Message message) {
        updated.add(message);
    }

    @Override
    public void onCleared() {
        cleared++;
    }
}
