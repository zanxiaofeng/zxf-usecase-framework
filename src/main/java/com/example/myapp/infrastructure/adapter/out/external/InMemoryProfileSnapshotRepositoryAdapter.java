package com.example.myapp.infrastructure.adapter.out.external;

import com.example.myapp.application.port.out.ProfileSnapshotRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 演示用内存实现：保存画像快照并附加快照 ID 与时间戳。
 */
@Component("profileSnapshotRepository")
public class InMemoryProfileSnapshotRepositoryAdapter implements ProfileSnapshotRepository {

    private final AtomicLong sequence = new AtomicLong(1000);
    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> save(Map<String, Object> profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>(profile);
        snapshot.put("snapshotId", "snap-" + sequence.incrementAndGet());
        snapshot.put("savedAt", OffsetDateTime.now().toString());
        store.put(String.valueOf(snapshot.get("snapshotId")), snapshot);
        return snapshot;
    }

    @Override
    public List<Map<String, Object>> findAll() {
        return List.copyOf(store.values());
    }
}
