package lwsp;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 4-bit topic IDs (0–15). Names are local labels, not sent on the wire after SUBSCRIBE. */
public final class LWSPTopics {

    public static final byte SCREEN = 1;
    public static final byte TELEMETRY = 2;
    public static final byte CONTROL = 3;

    private static final String[] NAMES = {
            "reserved/0", "screen", "telemetry", "control"
    };

    private LWSPTopics() {
    }

    public static String name(byte topicId) {
        if (topicId >= 0 && topicId < NAMES.length) {
            return NAMES[topicId];
        }
        return "topic/" + topicId;
    }

    /** Receiver-side subscription filter (MQTT SUBSCRIBE analogue). Empty = accept all. */
    public static final class SubscriptionFilter {
        private final Set<Byte> topics = new HashSet<>();

        public void subscribe(byte topicId) {
            topics.add(topicId);
        }

        public void unsubscribe(byte topicId) {
            topics.remove(topicId);
        }

        public boolean accepts(byte topicId) {
            return topics.isEmpty() || topics.contains(topicId);
        }

        public Set<Byte> subscribed() {
            return Collections.unmodifiableSet(topics);
        }
    }
}
