package de.flog99.mapgui;

import de.flog99.mapgui.prompt.PromptRegistry;

import java.util.concurrent.Executor;

/**
 * The few things a wall needs from the server, handed to it by MapGUI.
 *
 * <p>Grouped because they are all the same kind of thing: plumbing a wall cannot get for itself.
 *
 * @param transport  how pixels reach a client
 * @param prompts    providers for text input, so a field on a wall can still be typed into
 * @param mainThread where work coming back from somewhere else has to be run
 */
public record WallServices(MapTransport transport, PromptRegistry prompts, Executor mainThread) {
}
