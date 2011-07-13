package net.sf.briar.protocol;

import java.util.List;

import net.sf.briar.api.protocol.Batch;
import net.sf.briar.api.protocol.BatchId;
import net.sf.briar.api.protocol.Message;

interface BatchFactory {

	Batch createBatch(BatchId id, List<Message> messages);
}
