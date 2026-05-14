package io.github.lingjiuu;

import io.github.lingjiuu.cli.ConsoleAgentSessionRenderer;
import io.github.lingjiuu.session.AgentSession;
import io.github.lingjiuu.session.AgentSessionFactory;

public class App {
    public static void main( String[] args ) {
        AgentSessionFactory agentSessionFactory = AgentSessionFactory.createDefault();
        AgentSession agentSession = agentSessionFactory.openSession();
        agentSession.subscribe(new ConsoleAgentSessionRenderer(agentSession.toolRegistry()::findDefinition));
        agentSession.prompt("现在几点啊？");
    }
}
