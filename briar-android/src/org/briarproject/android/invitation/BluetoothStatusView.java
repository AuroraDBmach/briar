package org.briarproject.android.invitation;

import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
import static android.provider.Settings.ACTION_BLUETOOTH_SETTINGS;
import static android.view.Gravity.CENTER;
import static org.briarproject.android.util.CommonLayoutParams.WRAP_WRAP_1;

import org.briarproject.R;
import org.briarproject.android.util.LayoutUtils;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

class BluetoothStatusView extends LinearLayout implements OnClickListener {

	private final int pad;

	public BluetoothStatusView(Context ctx) {
		super(ctx);
		pad = LayoutUtils.getPadding(ctx);
	}

	void init() {
		setOrientation(HORIZONTAL);
		setGravity(CENTER);
		populate();
	}

	void populate() {
		removeAllViews();
		Context ctx = getContext();
		TextView status = new TextView(ctx);
		status.setLayoutParams(WRAP_WRAP_1);
		status.setTextSize(14);
		status.setPadding(pad, pad, pad, pad);
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if(adapter == null) {
			ImageView warning = new ImageView(ctx);
			warning.setPadding(pad, pad, pad, pad);
			warning.setImageResource(R.drawable.alerts_and_states_warning);
			addView(warning);
			status.setText(R.string.bluetooth_not_available);
			addView(status);
		} else if(adapter.getScanMode() == SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			ImageView ok = new ImageView(ctx);
			ok.setPadding(pad, pad, pad, pad);
			ok.setImageResource(R.drawable.navigation_accept);
			addView(ok);
			status.setText(R.string.bluetooth_discoverable);
			addView(status);
			ImageButton settings = new ImageButton(ctx);
			settings.setImageResource(R.drawable.action_settings);
			settings.setOnClickListener(this);
			addView(settings);
		} else if(adapter.isEnabled()) {
			ImageView warning = new ImageView(ctx);
			warning.setPadding(pad, pad, pad, pad);
			warning.setImageResource(R.drawable.alerts_and_states_warning);
			addView(warning);
			status.setText(R.string.bluetooth_not_discoverable);
			addView(status);
			ImageButton settings = new ImageButton(ctx);
			settings.setImageResource(R.drawable.action_settings);
			settings.setOnClickListener(this);
			addView(settings);
		} else {
			ImageView warning = new ImageView(ctx);
			warning.setPadding(pad, pad, pad, pad);
			warning.setImageResource(R.drawable.alerts_and_states_warning);
			addView(warning);
			status.setText(R.string.bluetooth_disabled);
			addView(status);
			ImageButton settings = new ImageButton(ctx);
			settings.setImageResource(R.drawable.action_settings);
			settings.setOnClickListener(this);
			addView(settings);
		}
	}

	public void onClick(View view) {
		getContext().startActivity(new Intent(ACTION_BLUETOOTH_SETTINGS));
	}
}
