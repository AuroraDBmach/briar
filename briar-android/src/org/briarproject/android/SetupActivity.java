package org.briarproject.android;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS;
import static android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.LinearLayout.VERTICAL;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_MATCH;
import static org.briarproject.android.util.CommonLayoutParams.WRAP_WRAP;
import static org.briarproject.api.crypto.PasswordStrengthEstimator.WEAK;

import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import org.briarproject.R;
import org.briarproject.android.util.LayoutUtils;
import org.briarproject.android.util.StrengthMeter;
import org.briarproject.api.AuthorFactory;
import org.briarproject.api.LocalAuthor;
import org.briarproject.api.android.ReferenceManager;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.CryptoExecutor;
import org.briarproject.api.crypto.KeyPair;
import org.briarproject.api.crypto.PasswordStrengthEstimator;
import org.briarproject.api.db.DatabaseConfig;
import org.briarproject.util.StringUtils;

import roboguice.activity.RoboActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class SetupActivity extends RoboActivity implements OnClickListener {

	@Inject @CryptoExecutor private Executor cryptoExecutor;
	@Inject private PasswordStrengthEstimator strengthEstimator;
	private EditText nicknameEntry = null;
	private EditText passwordEntry = null, passwordConfirmation = null;
	private StrengthMeter strengthMeter = null;
	private TextView feedback = null;
	private Button continueButton = null;
	private ProgressBar progress = null;

	// Fields that are accessed from background threads must be volatile
	@Inject private volatile CryptoComponent crypto;
	@Inject private volatile DatabaseConfig databaseConfig;
	@Inject private volatile AuthorFactory authorFactory;
	@Inject private volatile ReferenceManager referenceManager;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		int pad = LayoutUtils.getPadding(this);

		TextView chooseNickname = new TextView(this);
		chooseNickname.setGravity(CENTER);
		chooseNickname.setTextSize(18);
		chooseNickname.setPadding(pad, pad, pad, 0);
		chooseNickname.setText(R.string.choose_nickname);
		layout.addView(chooseNickname);

		nicknameEntry = new EditText(this) {
			@Override
			protected void onTextChanged(CharSequence text, int start,
					int lengthBefore, int lengthAfter) {
				enableOrDisableContinueButton();
			}
		};
		nicknameEntry.setId(1);
		nicknameEntry.setMaxLines(1);
		int inputType = TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_WORDS;
		nicknameEntry.setInputType(inputType);
		layout.addView(nicknameEntry);

		TextView choosePassword = new TextView(this);
		choosePassword.setGravity(CENTER);
		choosePassword.setTextSize(18);
		choosePassword.setPadding(pad, pad, pad, 0);
		choosePassword.setText(R.string.choose_password);
		layout.addView(choosePassword);

		passwordEntry = new EditText(this) {
			@Override
			protected void onTextChanged(CharSequence text, int start,
					int lengthBefore, int lengthAfter) {
				enableOrDisableContinueButton();
			}
		};
		passwordEntry.setId(2);
		passwordEntry.setMaxLines(1);
		inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD;
		passwordEntry.setInputType(inputType);
		layout.addView(passwordEntry);

		TextView confirmPassword = new TextView(this);
		confirmPassword.setGravity(CENTER);
		confirmPassword.setTextSize(18);
		confirmPassword.setPadding(pad, pad, pad, 0);
		confirmPassword.setText(R.string.confirm_password);
		layout.addView(confirmPassword);

		passwordConfirmation = new EditText(this) {
			@Override
			protected void onTextChanged(CharSequence text, int start,
					int lengthBefore, int lengthAfter) {
				enableOrDisableContinueButton();
			}
		};
		passwordConfirmation.setId(3);
		passwordConfirmation.setMaxLines(1);
		inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD;
		passwordConfirmation.setInputType(inputType);
		layout.addView(passwordConfirmation);

		strengthMeter = new StrengthMeter(this);
		strengthMeter.setPadding(5 * pad, pad, 5 * pad, 0);
		layout.addView(strengthMeter);

		feedback = new TextView(this);
		feedback.setGravity(CENTER);
		feedback.setTextSize(14);
		feedback.setPadding(pad, pad, pad, pad);
		feedback.setText("");
		layout.addView(feedback);

		continueButton = new Button(this);
		continueButton.setLayoutParams(WRAP_WRAP);
		continueButton.setText(R.string.continue_button);
		continueButton.setEnabled(false);
		continueButton.setOnClickListener(this);
		layout.addView(continueButton);

		progress = new ProgressBar(this);
		progress.setLayoutParams(WRAP_WRAP);
		progress.setIndeterminate(true);
		progress.setVisibility(GONE);
		layout.addView(progress);

		ScrollView scroll = new ScrollView(this);
		scroll.addView(layout);

		setContentView(scroll);
	}

	private void enableOrDisableContinueButton() {
		if(continueButton == null) return; // Not created yet
		boolean nicknameNotEmpty = nicknameEntry.getText().length() > 0;
		char[] firstPassword = getChars(passwordEntry.getText());
		char[] secondPassword = getChars(passwordConfirmation.getText());
		boolean passwordsMatch = Arrays.equals(firstPassword, secondPassword);
		float strength = strengthEstimator.estimateStrength(firstPassword);
		for(int i = 0; i < firstPassword.length; i++) firstPassword[i] = 0;
		for(int i = 0; i < secondPassword.length; i++) secondPassword[i] = 0;
		strengthMeter.setStrength(strength);
		if(firstPassword.length == 0) {
			feedback.setText("");
		} else if(secondPassword.length == 0 || passwordsMatch) {
			if(strength < PasswordStrengthEstimator.WEAK) {
				feedback.setText(R.string.password_too_weak);
			} else if(strength < PasswordStrengthEstimator.QUITE_WEAK) {
				feedback.setText(R.string.password_weak);
			} else if(strength < PasswordStrengthEstimator.QUITE_STRONG) {
				feedback.setText(R.string.password_quite_weak);
			} else if(strength < PasswordStrengthEstimator.STRONG) {
				feedback.setText(R.string.password_quite_strong);
			} else {
				feedback.setText(R.string.password_strong);
			}
		} else if(!passwordsMatch) {
			feedback.setText(R.string.passwords_do_not_match);
		} else {
			feedback.setText("");
		}
		boolean valid = nicknameNotEmpty && passwordsMatch && strength >= WEAK;
		continueButton.setEnabled(valid);
	}

	private char[] getChars(Editable e) {
		int length = e.length();
		char[] c = new char[length];
		e.getChars(0, length, c, 0);
		return c;
	}

	public void onClick(View view) {
		// Replace the feedback text and button with a progress bar
		feedback.setVisibility(GONE);
		continueButton.setVisibility(GONE);
		progress.setVisibility(VISIBLE);
		// Copy the passwords and erase the originals
		final String nickname = nicknameEntry.getText().toString();
		final char[] password = getChars(passwordEntry.getText());
		delete(passwordEntry.getText());
		delete(passwordConfirmation.getText());
		// Store the DB key and create the identity in a background thread
		cryptoExecutor.execute(new Runnable() {
			public void run() {
				byte[] key = crypto.generateSecretKey().getEncoded();
				byte[] encrypted = crypto.encryptWithPassword(key, password);
				storeEncryptedDatabaseKey(encrypted);
				databaseConfig.setEncryptionKey(key);
				KeyPair keyPair = crypto.generateSignatureKeyPair();
				final byte[] publicKey = keyPair.getPublic().getEncoded();
				final byte[] privateKey = keyPair.getPrivate().getEncoded();
				LocalAuthor a = authorFactory.createLocalAuthor(nickname,
						publicKey, privateKey);
				showHomeScreen(referenceManager.putReference(a,
						LocalAuthor.class));				
			}
		});
	}

	private void delete(Editable e) {
		e.delete(0, e.length());
	}

	private void storeEncryptedDatabaseKey(byte[] encrypted) {
		SharedPreferences prefs = getSharedPreferences("db", MODE_PRIVATE);
		Editor editor = prefs.edit();
		editor.putString("key", StringUtils.toHexString(encrypted));
		editor.commit();
	}

	private void showHomeScreen(final long handle) {
		runOnUiThread(new Runnable() {
			public void run() {
				Intent i = new Intent(SetupActivity.this,
						HomeScreenActivity.class);
				i.putExtra("org.briarproject.LOCAL_AUTHOR_HANDLE", handle);
				i.setFlags(FLAG_ACTIVITY_NEW_TASK);
				startActivity(i);
				finish();
			}
		});
	}
}
