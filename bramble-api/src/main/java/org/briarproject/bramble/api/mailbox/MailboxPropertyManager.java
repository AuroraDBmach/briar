package org.briarproject.bramble.api.mailbox;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.ClientId;

import javax.annotation.Nullable;

@NotNullByDefault
public interface MailboxPropertyManager {

	/**
	 * The unique ID of the mailbox property client.
	 */
	ClientId CLIENT_ID =
			new ClientId("org.briarproject.bramble.mailbox.properties");

	/**
	 * The current major version of the mailbox property client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the mailbox property client.
	 */
	int MINOR_VERSION = 0;

	/**
	 * The number of properties required for a (non-empty) update message.
	 */
	int PROP_COUNT = 4;

	/**
	 * The required properties of a non-empty update message.
	 */
	String PROP_KEY_ONIONADDRESS = "onionAddress";
	String PROP_KEY_AUTHTOKEN = "authToken";
	String PROP_KEY_INBOXID = "inboxId";
	String PROP_KEY_OUTBOXID = "outboxId";

	/**
	 * The length of all byte[] properties' values.
	 */
	int PROP_BYTES_LENGTH = 32;

	/**
	 * Message metadata key for the version number of a local or remote update,
	 * as a BDF long.
	 */
	String MSG_KEY_VERSION = "version";

	/**
	 * Message metadata key for whether an update is local or remote, as a BDF
	 * boolean.
	 */
	String MSG_KEY_LOCAL = "local";

	void createAndSendProperties(ContactId c) throws DbException;

	void sendEmptyProperties(ContactId c);

	@Nullable
	MailboxPropertiesUpdate getRemoteProperties(ContactId c);
}
