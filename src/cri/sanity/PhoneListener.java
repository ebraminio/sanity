package cri.sanity;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;


public final class PhoneListener extends PhoneStateListener
{
	public  static final int   LISTEN = LISTEN_CALL_STATE|LISTEN_CALL_FORWARDING_INDICATOR;
	private static final int   FORCE_AUTOSPEAKER_DELAY = Conf.FORCE_AUTOSPEAKER_DELAY;
	private static final float PROXIM_FAR = 0.2f;

	private static final int TASK_DEVS    = Task.idNew();
	private static final int TASK_SPEAKER = Task.idNew();

	private static PhoneListener activeInst;

	public int btCount;
	public SpeakerListener speakerListener;

	private String  callNumber;
	private int     calls, lastCallState;
	private boolean outgoing, shutdown, notifyEnable, notifyDisable;
	private boolean proximRegistered, proximReverse, proximDisable, proximEnable;
	private boolean skipHeadset, autoSpeaker, loudSpeaker, speakerCall, headsetOn, wiredHeadsetOn;
	private boolean mobdataAuto, wifiAuto, gpsAuto, btAuto, skipBtConn, screenOff, screenOn, admin, rec;
	private boolean lastFar;
	private long    disableDelay, enableDelay, speakerDelay, speakerCallDelay;
	private int     volRestore, volPhone, volWired, volBt;
	private boolean volSolo;

	private final Task taskSpeakerOn  = new Task(){ public void run(){ autoSpeaker(true ); }};
	private final Task taskSpeakerOff = new Task(){ public void run(){ autoSpeaker(false); }};
	private final Task taskDevsOn     = new Task(){ public void run(){ enableDevs (true ); }};
	private final Task taskDevsOff    = new Task(){ public void run(){ enableDevs (false); }};
	private       Task taskForceSpeakerOn;

	private final Sensor proximSensor = Dev.sensorProxim();

	//---- inner classes

	private final SensorEventListener proximListener = new SensorEventListener()
	{
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) { }
		@Override
		public void	onSensorChanged(SensorEvent evt) {
			if(shutdown) return;
			final float   val = evt.values[0];
			final boolean far = proximReverse? val<PROXIM_FAR : val>=PROXIM_FAR;
			//A.logd("proximity sensor value = "+val);
			if(far == lastFar) return;
			if((!headsetOn || !skipHeadset) && isOffhook()) {
				if(autoSpeaker && !headsetOn) {
					if(far) taskSpeakerOn .exec(TASK_SPEAKER, speakerDelay);
					else 		taskSpeakerOff.exec(TASK_SPEAKER, 0);
				}
				if(proximDisable && (!far || proximEnable)) {
					if(far) taskDevsOn .exec(TASK_DEVS,  enableDelay);
					else    taskDevsOff.exec(TASK_DEVS, disableDelay);
				}
				screenOff(!far);
			}
			lastFar = far;
		}
	};

	private final BroadcastReceiver headsetWiredReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context c, Intent i) {
			final boolean on = i.getIntExtra("state",0) != 0;
			if(on == wiredHeadsetOn) return;
			updateHeadset(wiredHeadsetOn = on, volWired);
		}
	};

	//---- static methods

	public static final PhoneListener getActiveInstance() { return activeInst; }
	public static final boolean       isRunning()         { return activeInst != null; }

	//---- methods

	public final void startup()
	{
		//A.logd("PhoneListener.startup");
		activeInst = this;
		calls      = 0;
		outgoing   = true;
		callNumber = null;
		shutdown   = false;
		lastFar    = true;
		btCount    = Math.max(A.geti(K.BT_COUNT), 0);
		admin      = Admin.isActive();
		speakerListener  = null;
		lastCallState    = TelephonyManager.CALL_STATE_IDLE;
		skipHeadset      = A.is(K.SKIP_HEADSET);
		skipBtConn       = A.is(K.SKIP_BT);
		notifyEnable     = A.is(K.NOTIFY_ENABLE);
		notifyDisable    = A.is(K.NOTIFY_DISABLE);
		proximDisable    = A.is(K.DISABLE_PROXIMITY) && proximSensor!=null;
		proximEnable     = A.is(K.ENABLE_PROXIMITY ) && proximDisable;
		proximReverse    = A.is(K.REVERSE_PROXIMITY);
		autoSpeaker      = A.is(K.SPEAKER_AUTO) && proximSensor!=null;
		loudSpeaker      = A.is(K.SPEAKER_LOUD);
		speakerCall      = A.is(K.SPEAKER_CALL);
		screenOff        = A.is(K.SCREEN_OFF);
		screenOn         = A.is(K.SCREEN_ON);
		speakerDelay     = A.getsi(K.SPEAKER_DELAY);
		speakerCallDelay = A.getsi(K.SPEAKER_CALL_DELAY);
		disableDelay     = A.getsi(K.DISABLE_DELAY);
		enableDelay      = A.getsi(K.ENABLE_DELAY);
		if(enableDelay < 0) enableDelay = disableDelay;
		volRestore = -1;
		volPhone   = A.getsi(K.VOL_PHONE);
		volWired   = A.getsi(K.VOL_WIRED);
		volBt      = A.getsi(K.VOL_BT);
		volSolo    = A.is(K.VOL_SOLO);
		volSolo(true);
		if(A.is(K.NOTIFY_VOLUME)) Dev.defVolFlags = Dev.FLAG_VOL_SHOW;
		final boolean hotspot = A.is(K.SKIP_HOTSPOT) && Dev.isHotspotOn();
		final boolean tether  = A.is(K.SKIP_TETHER) && Dev.isTetheringOn();
		gpsAuto     = A.is(K.AUTO_GPS) && Dev.isGpsOn();
		wifiAuto    = !hotspot && A.is(K.AUTO_WIFI) && Dev.isWifiOn();
		mobdataAuto = !hotspot && !tether && (!gpsAuto || !A.is(K.SKIP_MOBDATA)) && A.is(K.AUTO_MOBDATA) && Dev.isMobDataOn();
		btAuto      = A.is(K.AUTO_BT) && Dev.isBtOn();
		headsetOn   = skipHeadset && (Dev.isHeadsetOn() || (btCount>0 && A.is(K.FORCE_BT_AUDIO)));
		wiredHeadsetOn = skipHeadset && A.audioMan().isWiredHeadsetOn();
		// start multithreading and call recorder service if enabled
		Task.start();
		if(rec = A.is(K.REC)) RecService.start(this);
		// register listeners
		regHeadset();
		regProximity();
		Dev.enableLock(false);
	}

	public final int     getState     () { return lastCallState; }
	public final boolean isRinging    () { return lastCallState == TelephonyManager.CALL_STATE_RINGING; }
	public final boolean isIdle       () { return lastCallState == TelephonyManager.CALL_STATE_IDLE   ; }
	public final boolean isOffhook    () { return lastCallState == TelephonyManager.CALL_STATE_OFFHOOK; }
	public final boolean isShutdown   () { return shutdown; }
	public final boolean isOutgoing   () { return outgoing; }
	public final boolean isCallSpeaker() { return lastFar && speakerCall; }
	public final boolean isHeadsetOn  () { return headsetOn; }
	public final boolean hasAutoDev   () { return mobdataAuto || wifiAuto || btAuto || gpsAuto; }
	public final String  callNumber   () { return callNumber; }

	public final void updateHeadsetBt(boolean on)
	{
		if(wiredHeadsetOn) return;	// if wired headset are on, then the new bt device connected is NOT headset one!
		updateHeadset(on, volBt);
	}

	private void updateHeadset(boolean on, int vol)
	{
		if(headsetOn == on) return;
		headsetOn = on;
		setCallVolume(on, vol);
		final boolean on2 = on || (lastFar && proximDisable);
		if(autoSpeaker) autoSpeaker(on2);
		enableDevs(on2);
		screenOff(!on2);
		if(rec) RecService.updateHeadset(on);
		//A.logd("headset connected: "+on);
	}

	// invoked when headsets are (un)plugged for automatic volume (un)setting
	private void setCallVolume(boolean on, int vol)
	{
		if(vol < 0) return;
		if(!on)
			vol = volPhone;
		else if(volPhone < 0)
			volPhone = volRestore<0 ? Dev.getVolume(Dev.VOL_CALL) : volRestore;
		Dev.setVolume(Dev.VOL_CALL, vol);
		//A.logd((on?"preferred":"restored")+" volume set to level "+vol);
	}

	private void onRinging(String number)
	{
		//A.logd("onRinging");
		outgoing = false;
		if(!A.empty(number)) callNumber = number;
	}

	// we have a call!
	private void onOffhook(String number)
	{
		//A.logd("onOffhook");
		if(callNumber == null) callNumber = A.empty(number)? PhoneReceiver.number : number;
		PhoneReceiver.number = null;
		if(headsetOn)
			setCallVolume(true, wiredHeadsetOn? volWired : volBt);
		else {
			if(volPhone >= 0)
				Dev.setVolume(Dev.VOL_CALL, volPhone);
			if(isCallSpeaker()) {
				if(outgoing)
					forceAutoSpeakerOn();
				else if(speakerCallDelay > 0)
					taskSpeakerOn.exec(TASK_SPEAKER, speakerCallDelay);
				else
					autoSpeaker(true);
			}
		}
		if(rec) RecService.checkAutoRec();
		if(!proximDisable) enableDevs(false);
		//if(!screenOn) screenOff(true);
	}

	// call completed: restore & shutdown
	private void onIdle()
	{
		//A.logd("onIdle");
		shutdown = true;
		unregProximity();
		unregHeadset();
		if(rec) RecService.stop();
		Task.stop();
		if(!headsetOn && A.is(K.SPEAKER_SILENT_END)) Dev.enableSpeaker(false);
		if(volRestore >= 0) Dev.setVolume(Dev.VOL_CALL, volRestore);
		enableDevs(true);
		volSolo(false);
		Dev.restoreScreenTimeout();
		screenOff(false);
		if(A.is(K.VIBRATE_END)) A.vibrate();
		Dev.enableLock(true);
		callNumber = null;
		activeInst = null;
		MainService.stop();
		System.gc();
	}

	private synchronized void enableDevs(boolean enable)
	{
		if(!enable && headsetOn && skipHeadset) return;
		boolean done = false;
		if(gpsAuto  && enable!=Dev.isGpsOn ())                         { Dev.toggleGps();           done = true; }
		if(wifiAuto && enable!=Dev.isWifiOn())                         { Dev.enableWifi(enable);    done = true; }
		if(mobdataAuto && enable!=Dev.isMobDataOn())                   { Dev.enableMobData(enable); done = true; }
		if(btAuto && (!skipBtConn||btCount<1) && enable!=Dev.isBtOn()) { Dev.enableBt(enable);      done = true; }
		if(!done) return;
		if(enable) { if(notifyEnable ) { A.notify(A.tr(R.string.msg_devs_enabled )); if(rec) A.notifyCanc(); }}
		else       { if(notifyDisable) { A.notify(A.tr(R.string.msg_devs_disabled)); if(rec) A.notifyCanc(); }}
		//A.logd("enableDevs: "+enable);
	}

	private void autoSpeaker(boolean far)
	{
		if(headsetOn || far==Dev.isSpeakerOn()) return;
		if(far && loudSpeaker && volRestore<0)
			volRestore = volPhone>=0 ? volPhone : Dev.getVolume(Dev.VOL_CALL);  // retrieve current call volume (to restore later)
		Dev.enableSpeaker(far);
		//A.logd("auto set speaker: far="+far);
		if(loudSpeaker) {
			if(far) {
				Dev.setVolumeMax(Dev.VOL_CALL);
				//A.logd("auto set loud speaker");
			} else if(volRestore >= 0) {
				Dev.setVolume(Dev.VOL_CALL, volRestore);
				//A.logd("restore (from loud speaker) volume to "+volRestore);
				//volRestore = -1;
			}
		}
		if(speakerListener != null) speakerListener.onSpeakerChanged(far);
	}
	
	private void forceAutoSpeakerOn()
	{
		if(speakerCallDelay <= 0) {
			final SpeakerListener sl = speakerListener;
			speakerListener = null;
			autoSpeaker(true);
			speakerListener = sl;
		}
		if(taskForceSpeakerOn == null)
			taskForceSpeakerOn = new Task(){ public void run(){ if(lastFar) autoSpeaker(true); }};
		// enable speaker again after a little delay (some phones auto-disable speaker on offhook)
		taskForceSpeakerOn.exec(TASK_SPEAKER, Math.max(speakerCallDelay, FORCE_AUTOSPEAKER_DELAY));
    //A.logd("force auto speaker on");
	}

	private void screenOff(boolean off)
	{
		if(off) {
			if(!screenOff) return;
			if(!admin)
				Dev.setScreenOffTimeout(Conf.CALL_SCREEN_TIMEOUT);
			else {
				try {
					A.devpolMan().lockNow();
				} catch(Exception e) {
					admin = false;
					Dev.setScreenOffTimeout(Conf.CALL_SCREEN_TIMEOUT);
				}
			}
		} else {
			if(!screenOn) return;
			if(admin) Dev.wakeScreen();
			else Dev.restoreScreenTimeout();
		}
		//A.logd("screenOff: "+off);
	}
	
	private void volSolo(boolean enable)
	{
		if(!volSolo) return;
		Dev.mute(Dev.VOL_SYS   , enable);
		Dev.mute(Dev.VOL_ALARM , enable);
		Dev.mute(Dev.VOL_NOTIFY, enable);
		//Dev.mute(Dev.VOL_DTMF  , enable);
		//Dev.mute(Dev.VOL_MEDIA , enable);		// if we mute media, we cannot record phone call!
		//A.logd("set volume solo: "+enable);
	}

	private void regProximity() {
		proximRegistered = proximSensor!=null && (autoSpeaker || screenOff || screenOn || (proximDisable && hasAutoDev()));
		if(!proximRegistered) return;
		A.sensorMan().registerListener(proximListener, proximSensor, SensorManager.SENSOR_DELAY_NORMAL);
	}
	private void unregProximity() {
		if(!proximRegistered) return;
		proximRegistered = false;
		try { A.sensorMan().unregisterListener(proximListener); }
		catch(Exception e) {}
	}

	private void regHeadset() {
		A.app().registerReceiver(headsetWiredReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
	}
	private void unregHeadset() {
		try { A.app().unregisterReceiver(headsetWiredReceiver); }
		catch(Exception e) {}
	}
	
	//---- PhoneStateListener implementation

	@Override
	public void onCallForwardingIndicatorChanged(boolean cfi)
	{
		if(outgoing && !headsetOn && isCallSpeaker() && isOffhook()) forceAutoSpeakerOn();
		//A.logd("onCallForwardingIndicatorChanged: "+cfi);
	}

	@Override
	public void onCallStateChanged(int state, String incomingNumber)
	{
		// check against "calls" counter to skip multiple/concurrent phone calls
		switch(state) {
			case TelephonyManager.CALL_STATE_RINGING:
				if(isIdle()) onRinging(incomingNumber);
				break;
			case TelephonyManager.CALL_STATE_OFFHOOK:
				if(++calls == 1) onOffhook(incomingNumber);
				break;
			case TelephonyManager.CALL_STATE_IDLE:
				if(--calls > 0) break;
				onIdle();
				calls = 0;
				break;
		}
		lastCallState = state;
		//A.logd("onCallStateChanged: state="+state+", calls="+calls);
	}

}
