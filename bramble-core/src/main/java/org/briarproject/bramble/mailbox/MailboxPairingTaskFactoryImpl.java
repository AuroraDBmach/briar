package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.mailbox.MailboxPairingTask;
import org.briarproject.bramble.api.mailbox.MailboxSettingsManager;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
class MailboxPairingTaskFactoryImpl implements MailboxPairingTaskFactory {

	private final Executor eventExecutor;
	private final TransactionManager db;
	private final CryptoComponent crypto;
	private final MailboxApi api;
	private final MailboxSettingsManager mailboxSettingsManager;

	@Inject
	MailboxPairingTaskFactoryImpl(Executor eventExecutor,
			TransactionManager db,
			CryptoComponent crypto,
			MailboxApi api,
			MailboxSettingsManager mailboxSettingsManager) {
		this.eventExecutor = eventExecutor;
		this.db = db;
		this.crypto = crypto;
		this.api = api;
		this.mailboxSettingsManager = mailboxSettingsManager;
	}

	@Override
	public MailboxPairingTask createPairingTask(String qrCodePayload) {
		return new MailboxPairingTaskImpl(qrCodePayload, eventExecutor, db,
				crypto, api, mailboxSettingsManager);
	}
}
