package com.example.myapp.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * 用户画像快照出端口 —— 演示 dataSaver 步骤。
 */
public interface ProfileSnapshotRepository {

    /** 保存快照，返回附带快照 ID 与保存时间的副本 */
    Map<String, Object> save(Map<String, Object> profile);

    List<Map<String, Object>> findAll();
}
