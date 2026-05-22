package io.github.lingjiuu.ui.approval;

import io.github.lingjiuu.tool.permission.ApprovalHandler;
import io.github.lingjiuu.tool.permission.ApprovalId;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ApprovalCoordinator implements ApprovalHandler {

    private final Map<String, CompletableFuture<ApprovalResponse>> pending = new ConcurrentHashMap<>();
    private final ApprovalHandler fallback;

    public ApprovalCoordinator() {
        this(null);
    }

    public ApprovalCoordinator(ApprovalHandler fallback) {
        this.fallback = fallback;
    }

    @Override
    public ApprovalResponse requestApproval(ApprovalRequest request) {
        if (request == null || request.id() == null) {
            return null;
        }
        if (fallback != null) {
            return fallback.requestApproval(request);
        }
        CompletableFuture<ApprovalResponse> future = new CompletableFuture<>();
        pending.put(request.id().value(), future);
        try {
            ApprovalResponse response = future.join();
            if (response == null || !request.id().equals(response.id())) {
                return ApprovalResponse.deny(request.id(), "Approval response did not match pending request.");
            }
            return response;
        } finally {
            pending.remove(request.id().value());
        }
    }

    public boolean hasPending(String approvalId) {
        return approvalId != null && pending.containsKey(approvalId);
    }

    public boolean resolve(String approvalId, boolean approved, String reason) {
        if (approvalId == null || approvalId.isBlank()) {
            return false;
        }
        CompletableFuture<ApprovalResponse> future = pending.get(approvalId);
        if (future == null) {
            return false;
        }
        ApprovalId id = new ApprovalId(approvalId);
        ApprovalResponse response = approved
                ? ApprovalResponse.approve(id)
                : ApprovalResponse.deny(id, reason);
        return future.complete(response);
    }
}
