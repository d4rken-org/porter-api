package eu.darken.porter.sdk;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PorterServiceConnections {

    private static final Map<String, PorterServiceConnection> CACHE = Collections.synchronizedMap(new HashMap<>());

    @NonNull
    static PorterServiceConnection get(Porter.UserServiceArgs args) {
        String key = args.tag != null ? args.tag : args.componentName.getClassName();
        PorterServiceConnection connection = CACHE.get(key);

        if (connection == null) {
            connection = new PorterServiceConnection(args);
            CACHE.put(key, connection);
        }
        return connection;
    }

    static void remove(PorterServiceConnection connection) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, PorterServiceConnection> entry : CACHE.entrySet()) {
            if (entry.getValue() == connection) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CACHE.remove(key);
        }
    }
}
