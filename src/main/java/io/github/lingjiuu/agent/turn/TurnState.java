package io.github.lingjiuu.agent.turn;

public class TurnState {

    private int samplingCount;
    private int toolCallCount;

    public int samplingCount() {
        return samplingCount;
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public int nextSampling() {
        samplingCount++;
        return samplingCount;
    }

    public void addToolCalls(int count) {
        if (count > 0) {
            toolCallCount += count;
        }
    }
}
