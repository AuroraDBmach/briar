package net.sf.briar.api.protocol;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

/**
 * An interface for writing a bundle of acknowledgements, subscriptions,
 * transport details and batches.
 */
public interface BundleWriter {

	/** Returns the bundle's remaining capacity in bytes. */
	long getRemainingCapacity() throws IOException;

	/** Adds a header to the bundle. */
	void addHeader(Iterable<BatchId> acks, Iterable<GroupId> subs,
			Map<String, String> transports) throws IOException,
			GeneralSecurityException;

	/** Adds a batch to the bundle and returns its identifier. */
	BatchId addBatch(Iterable<Message> messages) throws IOException,
	GeneralSecurityException;

	/** Finishes writing the bundle. */
	void finish() throws IOException;
}
