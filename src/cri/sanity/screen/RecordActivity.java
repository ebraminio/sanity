package cri.sanity.screen;

import cri.sanity.*;
import java.io.File;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;


public class RecordActivity extends ScreenActivity
{
	//---- Activity override

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		on(K.REC_BROWSE, new Click(){ public boolean on(){
			startActivity(new Intent(A.app(), BrowseActivity.class));
			return true;
		}});
		on(K.REC_SCAN,  new Change(){ public boolean on(){
			boolean res = (Boolean)value? scanEnable() : scanDisable();
			if(!res) A.alert(A.tr(R.string.msg_rec_scan_err).replace("$FN", scanFn()));
			return res;
		}});
		setEnabled(K.REC_START_SPEAKER, A.is(K.REC_START) && (A.is(K.SPEAKER_AUTO) || A.is(K.SPEAKER_CALL)));
		setEnabled(K.REC_STOP_SPEAKER , A.is(K.REC_STOP ) && (A.is(K.SPEAKER_AUTO) || A.is(K.SPEAKER_CALL)));
		setEnabled(K.REC_STOP_LIMIT   , A.is(K.REC_STOP ) &&  A.isFull());
		setEnabled(K.REC_START_TIMES  , A.is(K.REC_START_SPEAKER) || A.getsi(K.REC_START_HEADSET)!=RecService.ACT_HEADSET_SKIP);
		on(K.REC_START, new Change(){ public boolean on(){
			final boolean on = (Boolean)value;
			setEnabled(K.REC_START_SPEAKER, on && (A.is(K.SPEAKER_AUTO) || A.is(K.SPEAKER_CALL)));
			return true;
		}});
		on(K.REC_STOP, new Change(){ public boolean on(){
			final boolean on = (Boolean)value;
			setEnabled(K.REC_STOP_SPEAKER, on && (A.is(K.SPEAKER_AUTO) || A.is(K.SPEAKER_CALL)));
			setEnabled(K.REC_STOP_LIMIT  , on &&  A.isFull());
			return true;
		}});
		on(K.REC_START_SPEAKER, new Change(){ public boolean on(){
			setEnabled(K.REC_START_TIMES, (Boolean)value || A.getsi(K.REC_START_HEADSET)!=RecService.ACT_HEADSET_SKIP);
			return true;
		}});
		on(K.REC_START_HEADSET, new Change(){ public boolean on(){
			setEnabled(K.REC_START_TIMES, !Integer.toString(RecService.ACT_HEADSET_SKIP).equals((String)value) || A.is(K.REC_START_SPEAKER));
			return true;
		}});
		if(!A.isFull()) {
			final Preference p = pref(K.REC_STOP_LIMIT);
			p.setEnabled(false);
			p.setSummary(p.getSummary()+" ("+A.tr(R.string.full_only)+".)");
		}
	}

	@Override
	public void onStart()
	{
		super.onStart();
		setChecked(K.REC_SCAN, scanAllowed());
	}

	//---- private static api

	private static boolean scanAllowed() { return !scanFile().exists(); }
	private static File    scanFile   () { return new File(scanFn());   }
	private static String  scanFn     () { return A.sdcardDir() + "/.nomedia"; }
	private static boolean scanEnable () {
		final File f = scanFile();
		try { f.delete(); } catch(Exception e) {}
		return !f.exists();
	}
	private static boolean scanDisable() {
		final File f = scanFile();
		try { f.createNewFile(); } catch(Exception e) {}
		return f.exists();
	}

}
