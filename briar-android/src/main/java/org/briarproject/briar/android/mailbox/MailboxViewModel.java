package org.briarproject.briar.android.mailbox;

import android.app.Application;

import com.google.zxing.Result;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.mailbox.MailboxAuthToken;
import org.briarproject.bramble.api.mailbox.MailboxProperties;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.mailbox.MailboxState.NotSetup;
import org.briarproject.briar.android.qrcode.QrCodeDecoder;
import org.briarproject.briar.android.viewmodel.DbViewModel;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;

@NotNullByDefault
class MailboxViewModel extends DbViewModel
		implements QrCodeDecoder.ResultCallback {

	private static final Logger LOG =
			getLogger(MailboxViewModel.class.getName());

	@SuppressWarnings("CharsetObjectCanBeUsed") // Requires minSdkVersion >= 19
	private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
	private static final int VERSION_REQUIRED = 32;

	private final CryptoComponent crypto;
	private final QrCodeDecoder qrCodeDecoder;
	private final PluginManager pluginManager;

	private final MutableLiveData<MailboxState> state = new MutableLiveData<>();

	@Inject
	MailboxViewModel(
			Application app,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor,
			CryptoComponent crypto,
			PluginManager pluginManager) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.crypto = crypto;
		this.pluginManager = pluginManager;
		qrCodeDecoder = new QrCodeDecoder(androidExecutor, ioExecutor, this);
		checkIfSetup();
	}

	@UiThread
	private void checkIfSetup() {
		runOnDbThread(() -> {
			// TODO really check if mailbox is setup/paired/linked
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			state.postValue(new NotSetup());
		});
	}

	@Override
	@IoExecutor
	public void onQrCodeDecoded(Result result) {
		LOG.info("Got result from decoder");
		MailboxProperties properties;
		try {
			properties = decodeQrCode(result.getText());
		} catch (FormatException e) {
			state.postValue(new MailboxState.QrCodeWrong());
			return;
		}
		onMailboxPropertiesReceived(properties);
	}

	// TODO move this into core #2168
	private MailboxProperties decodeQrCode(String payload)
			throws FormatException {
		byte[] bytes = payload.getBytes(ISO_8859_1);
		if (bytes.length != 65) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("QR code length is not 65: " + bytes.length);
			}
			throw new FormatException();
		}
		if (bytes[0] != VERSION_REQUIRED) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("QR code has not version " + VERSION_REQUIRED +
						": " + bytes[0]);
			}
			throw new FormatException();
		}
		LOG.info("QR code is valid");
		byte[] onionPubKey = Arrays.copyOfRange(bytes, 1, 33);
		String onionAddress = crypto.encodeOnionAddress(onionPubKey);
		byte[] tokenBytes = Arrays.copyOfRange(bytes, 33, 65);
		MailboxAuthToken setupToken = new MailboxAuthToken(tokenBytes);
		return new MailboxProperties(onionAddress, setupToken, true);
	}

	private void onMailboxPropertiesReceived(MailboxProperties properties) {
		if (isTorActive()) {
			// TODO pass props to core #2168
			state.postValue(new MailboxState.SettingUp());
		} else {
			state.postValue(new MailboxState.OfflineInSetup(properties));
		}
	}

	// TODO ideally also move this into core #2168
	private boolean isTorActive() {
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		return plugin != null && plugin.getState() == ACTIVE;
	}

	@UiThread
	void tryAgainWhenOffline() {
		MailboxState.OfflineInSetup offline =
				(MailboxState.OfflineInSetup) requireNonNull(state.getValue());
		onMailboxPropertiesReceived(offline.mailboxProperties);
	}

	@UiThread
	QrCodeDecoder getQrCodeDecoder() {
		return qrCodeDecoder;
	}

	@UiThread
	LiveData<MailboxState> getState() {
		return state;
	}

}
