package io.github.lingjiuu.tool.render;

public record ToolRenderedOutput(
        String text,
        ToolRenderShell shell,
        boolean hidden
) {

    public ToolRenderedOutput {
        shell = shell == null ? ToolRenderShell.DEFAULT : shell;
        text = text == null ? "" : text;
    }

    public static ToolRenderedOutput text(String text) {
        return new ToolRenderedOutput(text, ToolRenderShell.DEFAULT, false);
    }

    public static ToolRenderedOutput hiddenOutput() {
        return new ToolRenderedOutput("", ToolRenderShell.DEFAULT, true);
    }
}
