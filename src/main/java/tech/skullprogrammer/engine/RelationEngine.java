package tech.skullprogrammer.engine;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RelationEngine {

    private final ConcurrentHashMap<String, List<Map<String, Object>>> store = new ConcurrentHashMap<>();

    public void put(String resourceType, List<Map<String, Object>> items) {
        store.put(resourceType, items);
    }

    public List<Map<String, Object>> get(String resourceType) {
        return store.getOrDefault(resourceType, new ArrayList<>());
    }

    public Map<String, Object> getById(String resourceType, String id) {
        return get(resourceType).stream()
                .filter(item -> id.equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElse(null);
    }

    public void clear(String resourceType) {
        store.remove(resourceType);
    }

    public void clearAll() {
        store.clear();
    }
}