/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2010 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sk.vx.connectbot.service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.List;

import sk.vx.connectbot.R;
import sk.vx.connectbot.TerminalView;
import sk.vx.connectbot.bean.SelectionArea;
import sk.vx.connectbot.util.PreferenceConstants;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

/**
 * @author kenny
 *
 */
@SuppressWarnings("deprecation") // for ClipboardManager
public class TerminalKeyListener implements OnKeyListener, OnSharedPreferenceChangeListener {
	private static final String TAG = "ConnectBot.OnKeyListener";

	private final TerminalManager manager;
	private final TerminalBridge bridge;
	private final VDUBuffer buffer;
	private final vt320 vt;

	private String customKeyboard = null;

	public static int CTRL_LEFT_MASK = 0x001;
	public static int CTRL_RIGHT_MASK = 0x002;
	public static int SHIFT_LEFT_MASK = 0x004;
	public static int SHIFT_RIGHT_MASK = 0x008;
	public static int ALT_LEFT_MASK = 0x010;
	public static int ALT_RIGHT_MASK = 0x020;
	public static int CTRL_ANY_MASK = CTRL_LEFT_MASK | CTRL_RIGHT_MASK;
	public static int SHIFT_ANY_MASK = SHIFT_LEFT_MASK | SHIFT_RIGHT_MASK;
	public static int ALT_ANY_MASK = ALT_LEFT_MASK | ALT_RIGHT_MASK;

	private int metaState = 0;

	private int mDeadKey = 0;

	// TODO add support for the new API.
	private ClipboardManager clipboard = null;

	private final SelectionArea selectionArea;

	private String encoding;

	private final SharedPreferences prefs;

	public TerminalKeyListener(TerminalManager manager,
			TerminalBridge bridge,
			VDUBuffer buffer,
			String encoding) {
		this.manager = manager;
		this.bridge = bridge;
		this.buffer = buffer;
		this.vt = (vt320) buffer;
		this.encoding = encoding;

		selectionArea = bridge.getSelectionArea();

		prefs = PreferenceManager.getDefaultSharedPreferences(manager);
		prefs.registerOnSharedPreferenceChangeListener(this);

		updateCustomKeymap();
	}

	/**
	 * Handle onKey() events coming down from a {@link TerminalView} above us.
	 * Modify the keys to make more sense to a host then pass it to the transport.
	 */
	public boolean onKey(View v, int keyCode, KeyEvent event) {

		// I simply don't care about other hardware keyboards
		if (!customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_FULL))
			return false;

		try {
			switch (event.getAction()) {
			case KeyEvent.ACTION_MULTIPLE:
				return handleMultipleKeyDown(v, event);
			case KeyEvent.ACTION_DOWN:
				return handleKeyDown(v, event);
			case KeyEvent.ACTION_UP:
				return handleKeyUp(v, event);
			default:
				Log.d(TAG, "Unknown action type");
			}
		} catch (IOException e) {
			Log.e(TAG, "Problem while trying to handle an onKey() event", e);
			try {
				bridge.transport.flush();
			} catch (IOException ioe) {
				Log.d(TAG, "Our transport was closed, dispatching disconnect event");
				bridge.dispatchDisconnect(false);
			}
		} catch (NullPointerException npe) {
			Log.d(TAG, "Input before connection established ignored.");
			return true;
		}

		return false;
	}

	public boolean handleMultipleKeyDown(View v, KeyEvent event) throws UnsupportedEncodingException, IOException {
		byte[] input = event.getCharacters().getBytes(encoding);
		bridge.transport.write(input);
		return true;
	}

	public boolean handleKeyDown(View v, KeyEvent event) throws IOException {
		// special cases based on scancodes which are hardware dependent!
		switch (event.getScanCode()) {
		case 29:
			metaKeyDown(CTRL_LEFT_MASK);
			return true;
		case -1:
			metaKeyDown(CTRL_RIGHT_MASK);
			return true;
		case 56:
			metaKeyDown(ALT_LEFT_MASK);
			return true;
		case 100:
			metaKeyDown(ALT_RIGHT_MASK);
			return true;
		case 42:
			metaKeyDown(SHIFT_LEFT_MASK);
			return true;
		case 54:
			metaKeyDown(SHIFT_RIGHT_MASK);
			return true;
		case 14:
			vt.keyPressed(vt320.KEY_BACK_SPACE, ' ', getVtMetaState());
			return true;
		case 172:
			sendEscape();
			return true;
		case 15:
			if (bridge.isSelectingForCopy()) {
				if (selectionArea.isSelectingOrigin())
					selectionArea.finishSelectingOrigin();
				else {
					if (clipboard != null) {
						String copiedText = selectionArea.copyFrom(buffer);
						clipboard.setText(copiedText);
						((TerminalView)v).notifyUser(manager.getString(R.string.console_copy_done, copiedText.length()));
						selectionArea.reset();
						bridge.setSelectingForCopy(false);
						bridge.redraw();
					}
				}
				return true;
			}
			bridge.transport.write(0x09);
			return true;
		case 110:
			vt.keyPressed(vt320.KEY_INSERT, ' ', getVtMetaState());
			return true;
		case 111:
			vt.keyPressed(vt320.KEY_DELETE, ' ', 0);
			return true;
		case 105:
			if (bridge.isSelectingForCopy()) {
				bridge.getSelectionArea().decrementColumn();
				bridge.redraw();
				return true;
			}
			vt.keyPressed(vt320.KEY_LEFT, ' ', getVtMetaState());
			return true;
		case 103:
			if (bridge.isSelectingForCopy()) {
				bridge.getSelectionArea().decrementRow();
				bridge.redraw();
				return true;
			}
			vt.keyPressed(vt320.KEY_UP, ' ', getVtMetaState());
			return true;
		case 106:
			if (bridge.isSelectingForCopy()) {
				bridge.getSelectionArea().incrementColumn();
				bridge.redraw();
				return true;
			}
			vt.keyPressed(vt320.KEY_RIGHT, ' ', getVtMetaState());
			return true;
		case 108:
			if (bridge.isSelectingForCopy()) {
				bridge.getSelectionArea().incrementRow();
				bridge.redraw();
				return true;
			}
			vt.keyPressed(vt320.KEY_DOWN, ' ', getVtMetaState());
			return true;
		case 59:
			vt.keyPressed(vt320.KEY_F1, ' ', getVtMetaState());
			return true;
		case 60:
			vt.keyPressed(vt320.KEY_F2, ' ', getVtMetaState());
			return true;
		case 61:
			vt.keyPressed(vt320.KEY_F3, ' ', getVtMetaState());
			return true;
		case 62:
			vt.keyPressed(vt320.KEY_F4, ' ', getVtMetaState());
			return true;
		case 63:
			vt.keyPressed(vt320.KEY_F5, ' ', getVtMetaState());
			return true;
		case 64:
			vt.keyPressed(vt320.KEY_F6, ' ', getVtMetaState());
			return true;
		case 65:
			vt.keyPressed(vt320.KEY_F7, ' ', getVtMetaState());
			return true;
		case 66:
			vt.keyPressed(vt320.KEY_F8, ' ', getVtMetaState());
			return true;
		case 67:
			vt.keyPressed(vt320.KEY_F9, ' ', getVtMetaState());
			return true;
		case 68:
			vt.keyPressed(vt320.KEY_F10, ' ', getVtMetaState());
			return true;
		case 87:
			vt.keyPressed(vt320.KEY_F11, ' ', getVtMetaState());
			return true;
		case 88:
			vt.keyPressed(vt320.KEY_F12, ' ', getVtMetaState());
			return true;
		case 139:
			// menu
			return false;
		case 158:
			// back
			return false;
		}

		// special cases based on keycodes
		switch(event.getKeyCode()) {
		case 67:
			vt.keyPressed(vt320.KEY_BACK_SPACE, ' ', getVtMetaState());
			return true;
		}

		// special cases for polish diacritics
		if ((metaState & ALT_RIGHT_MASK) != 0) {
			char plchar = 0;
			switch(event.getUnicodeChar(0)) {
			case 'a':
				plchar = 'ą';
				break;
			case 'c':
				plchar = 'ć';
				break;
			case 'e':
				plchar = 'ę';
				break;
			case 'n':
				plchar = 'ń';
				break;
			case 'l':
				plchar = 'ł';
				break;
			case 'o':
				plchar = 'ó';
				break;
			case 's':
				plchar = 'ś';
				break;
			case 'x':
				plchar = 'ź';
				break;
			case 'z':
				plchar = 'ż';
				break;
			}

			if ((metaState & SHIFT_ANY_MASK) != 0) {
				bridge.transport.write(Character.valueOf(plchar).toString().toUpperCase().getBytes(encoding));
			} else {
				bridge.transport.write(Character.valueOf(plchar).toString().getBytes(encoding));
			}

			return true;
		}

		int uchar = 0;
		// <-- ugly special cases
		if (event.getKeyCode() == 75 && event.getMetaState() == 1) {
			uchar = '@';
		} else if (event.getKeyCode() == 77) {
			uchar = '"';
		} else if (event.getScanCode() == 41 && (metaState & SHIFT_ANY_MASK) != 0) {
			uchar = '~';
		} else if (event.getScanCode() == 41 && (metaState & SHIFT_ANY_MASK) == 0) {
			uchar = '`';
		} else if (event.getKeyCode() == 48 && event.getMetaState() == 3) {
			uchar = '#';
		} else if (event.getKeyCode() == 30 && event.getMetaState() == 2) {
			uchar = '<';
		}  else if (event.getKeyCode() == 42 && event.getMetaState() == 2) {
			uchar = '>';
		} // ugly special cases -->
		// apply shift
		else if ((metaState & SHIFT_ANY_MASK) != 0) {
			uchar = event.getUnicodeChar(KeyEvent.META_SHIFT_ON);
		} else {
			uchar = event.getUnicodeChar(0);
		}

		// apply ctrl
		if ((metaState & CTRL_ANY_MASK) != 0) {
			uchar = keyAsControl(uchar);
		}

		// apply left alt
		if ((metaState & ALT_LEFT_MASK) != 0) {
			sendEscape();
		}

		bridge.transport.write(uchar);
		return true;

	}

	public boolean handleKeyUp(View v, KeyEvent event) {
		switch (event.getScanCode()) {
		case 29:
			metaKeyUp(CTRL_LEFT_MASK);
			return true;
		case -1:
			metaKeyUp(CTRL_RIGHT_MASK);
			return true;
		case 56:
			metaKeyUp(ALT_LEFT_MASK);
			return true;
		case 100:
			metaKeyUp(ALT_RIGHT_MASK);
			return true;
		case 42:
			metaKeyUp(SHIFT_LEFT_MASK);
			return true;
		case 54:
			metaKeyUp(SHIFT_RIGHT_MASK);
			return true;
		}
		return false;
	}

	public int keyAsControl(int key) {
		// Support CTRL-a through CTRL-z
		if (key >= 0x61 && key <= 0x7A)
			key -= 0x60;
		// Support CTRL-A through CTRL-_
		else if (key >= 0x41 && key <= 0x5F)
			key -= 0x40;
		// CTRL-space sends NULL
		else if (key == 0x20)
			key = 0x00;
		// CTRL-? sends DEL
		else if (key == 0x3F)
			key = 0x7F;
		return key;
	}

	public void sendEscape() {
		((vt320)buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
	}

	/**
	 * Handle meta key presses for full hardware keyboard
	 */
	private void metaKeyDown(int code) {
		if ((metaState & code) == 0) {
			metaState |= code;
			bridge.redraw();
		}
	}

	private void metaKeyUp(int code) {
		if ((metaState & code) != 0) {
			metaState &= ~code;
			bridge.redraw();
		}
	}

	public void setTerminalKeyMode(String keymode) {
	}

	private int getVtMetaState() {
		int bufferState = 0;

		if ((metaState & CTRL_ANY_MASK) != 0)
			bufferState |= vt320.KEY_CONTROL;
		if ((metaState & SHIFT_ANY_MASK) != 0)
			bufferState |= vt320.KEY_SHIFT;
		if ((metaState & ALT_ANY_MASK) != 0)
			bufferState |= vt320.KEY_ALT;

		return bufferState;
	}

	public int getMetaState() {
		return metaState;
	}

	public int getDeadKey() {
		return mDeadKey;
	}

	public void setClipboardManager(ClipboardManager clipboard) {
		this.clipboard = clipboard;
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		if (PreferenceConstants.CUSTOM_KEYMAP.equals(key)) {
			updateCustomKeymap();
		}
	}

	private void updateCustomKeymap() {
		customKeyboard = prefs.getString(PreferenceConstants.CUSTOM_KEYMAP,
				PreferenceConstants.CUSTOM_KEYMAP_DISABLED);
	}

	public void setCharset(String encoding) {
		this.encoding = encoding;
	}

	public void urlScan(View v) {
		//final TerminalView terminalView = (TerminalView) findCurrentView(R.id.console_flip);

		List<String> urls = bridge.scanForURLs();

		Dialog urlDialog = new Dialog(v.getContext());
		urlDialog.setTitle(R.string.console_menu_urlscan);

		ListView urlListView = new ListView(v.getContext());
		URLItemListener urlListener = new URLItemListener(v.getContext());
		urlListView.setOnItemClickListener(urlListener);

		urlListView.setAdapter(new ArrayAdapter<String>(v.getContext(), android.R.layout.simple_list_item_1, urls));
		urlDialog.setContentView(urlListView);
		urlDialog.show();
	}

	private class URLItemListener implements OnItemClickListener {
		private WeakReference<Context> contextRef;

		URLItemListener(Context context) {
			this.contextRef = new WeakReference<Context>(context);
		}

		public void onItemClick(AdapterView<?> arg0, View view, int position,
				long id) {
			Context context = contextRef.get();

			if (context == null)
				return;

			try {
				TextView urlView = (TextView) view;

				String url = urlView.getText().toString();
				if (url.indexOf("://") < 0)
					url = "http://" + url;

				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
				context.startActivity(intent);
			} catch (Exception e) {
				Log.e(TAG, "couldn't open URL", e);
				// We should probably tell the user that we couldn't find a
				// handler...
			}
		}
	}
}

