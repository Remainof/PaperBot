package org.example.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.ShowCollectionsParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/milvus")
public class MilvusCheckController {

    @Autowired private MilvusServiceClient client;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var resp = client.showCollections(ShowCollectionsParam.newBuilder().build());
        if (resp.getStatus() == 0)
            return ResponseEntity.ok(Map.of("message", "ok", "collections", resp.getData().getCollectionNamesList()));
        return ResponseEntity.status(503).body(Map.of("message", resp.getMessage()));
    }
}
