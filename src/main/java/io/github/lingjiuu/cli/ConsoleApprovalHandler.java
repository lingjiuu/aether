package io.github.lingjiuu.cli;

import io.github.lingjiuu.tool.permission.ApprovalHandler;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;

import java.util.NoSuchElementException;
import java.util.Scanner;

public class ConsoleApprovalHandler implements ApprovalHandler {

    private final Scanner scanner;

    public ConsoleApprovalHandler() {
        this(new Scanner(System.in));
    }

    ConsoleApprovalHandler(Scanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public ApprovalResponse requestApproval(ApprovalRequest request) {
        if (request == null) {
            return null;
        }
        System.out.print("[APPROVAL] approve this tool call? [y/N] ");
        String answer;
        try {
            answer = scanner.nextLine();
        } catch (NoSuchElementException e) {
            return ApprovalResponse.deny(request.id(), "No approval response was provided.");
        }
        if ("y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer)) {
            return ApprovalResponse.approve(request.id());
        }
        return ApprovalResponse.deny(request.id(), "User denied approval.");
    }
}
