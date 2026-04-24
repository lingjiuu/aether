package io.github.lingjiuu.transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TranscriptChainBuilder {

    private final TranscriptRecordingPolicy policy;

    public TranscriptChainBuilder() {
        this(new TranscriptRecordingPolicy());
    }

    public TranscriptChainBuilder(TranscriptRecordingPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    public TranscriptChain build(List<TranscriptRecord> records) {
        if (records == null || records.isEmpty()) {
            return TranscriptChain.empty();
        }

        Map<String, TranscriptRecord> recordsById = new LinkedHashMap<>();
        Set<String> parentRecordIds = new LinkedHashSet<>();
        for (TranscriptRecord record : records) {
            if (record == null || record.getId() == null || record.getId().isBlank()) {
                continue;
            }
            recordsById.put(record.getId(), record);
            if (record.getParentRecordId() != null && !record.getParentRecordId().isBlank()) {
                parentRecordIds.add(record.getParentRecordId());
            }
        }

        TranscriptRecord leaf = latestLeaf(records, recordsById, parentRecordIds);
        if (leaf == null) {
            return TranscriptChain.empty();
        }
        return buildFromLeaf(recordsById, leaf);
    }

    private TranscriptRecord latestLeaf(
            List<TranscriptRecord> records,
            Map<String, TranscriptRecord> recordsById,
            Set<String> parentRecordIds
    ) {
        TranscriptRecord latest = null;
        for (TranscriptRecord record : records) {
            if (record == null || record.getId() == null) {
                continue;
            }
            if (recordsById.get(record.getId()) != record) {
                continue;
            }
            if (policy.canAnchorResume(record) && !parentRecordIds.contains(record.getId())) {
                latest = record;
            }
        }
        if (latest != null) {
            return latest;
        }
        for (TranscriptRecord record : records) {
            if (record != null && policy.canAnchorResume(record)) {
                latest = record;
            }
        }
        return latest;
    }

    private TranscriptChain buildFromLeaf(Map<String, TranscriptRecord> recordsById, TranscriptRecord leaf) {
        List<TranscriptRecord> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        TranscriptRecord current = leaf;
        while (current != null) {
            if (!seen.add(current.getId())) {
                break;
            }
            chain.add(current);
            String parentRecordId = current.getParentRecordId();
            current = parentRecordId == null || parentRecordId.isBlank()
                    ? null
                    : recordsById.get(parentRecordId);
        }
        Collections.reverse(chain);
        return new TranscriptChain(chain);
    }
}
