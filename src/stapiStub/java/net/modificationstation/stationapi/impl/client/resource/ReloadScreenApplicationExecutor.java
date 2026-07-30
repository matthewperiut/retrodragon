package net.modificationstation.stationapi.impl.client.resource;

import java.util.concurrent.Executor;

/** Compile stub. An enum upstream, and reproduced as one so {@code INSTANCE} resolves identically. */
public enum ReloadScreenApplicationExecutor implements Executor {
	INSTANCE;

	@Override
	public void execute(Runnable command) {
		throw new AssertionError("StationAPI compile stub");
	}

	public Runnable poll() {
		throw new AssertionError("StationAPI compile stub");
	}
}
