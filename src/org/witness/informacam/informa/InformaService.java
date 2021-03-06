package org.witness.informacam.informa;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.witness.informacam.R;
import org.witness.informacam.app.MainActivity;
import org.witness.informacam.app.editors.image.ImageConstructor;
import org.witness.informacam.app.editors.image.ImageRegion;
import org.witness.informacam.app.editors.image.ImageRegion.ImageRegionListener;
import org.witness.informacam.app.editors.video.RegionTrail;
import org.witness.informacam.app.editors.video.RegionTrail.VideoRegionListener;
import org.witness.informacam.app.editors.video.VideoConstructor;
import org.witness.informacam.informa.Informa;
import org.witness.informacam.informa.Informa.InformaListener;
import org.witness.informacam.informa.SensorLogger.OnUpdateListener;
import org.witness.informacam.informa.suckers.AccelerometerSucker;
import org.witness.informacam.informa.suckers.GeoSucker;
import org.witness.informacam.informa.suckers.PhoneSucker;
import org.witness.informacam.storage.IOCipherService;
import org.witness.informacam.storage.IOUtility;
import org.witness.informacam.utils.Constants;
import org.witness.informacam.utils.InformaMediaScanner;
import org.witness.informacam.utils.Constants.Informa.CaptureEvent;
import org.witness.informacam.utils.Constants.Informa.Keys.Data;
import org.witness.informacam.utils.Constants.Informa.Status;
import org.witness.informacam.utils.Constants.Informa.Keys.Genealogy;
import org.witness.informacam.utils.Constants.Informa.Keys.Data.Exif;
import org.witness.informacam.utils.Constants.App;
import org.witness.informacam.utils.Constants.Media;
import org.witness.informacam.utils.Constants.Settings;
import org.witness.informacam.utils.Constants.Storage;
import org.witness.informacam.utils.Constants.Time;
import org.witness.informacam.utils.Constants.Suckers.Phone;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.widget.RemoteViews;

public class InformaService extends Service implements OnUpdateListener, InformaListener, ImageRegionListener, VideoRegionListener {
	public static InformaService informaService;
	private final IBinder binder = new LocalBinder();

	NotificationManager nm;

	Intent toMainActivity;
	private String informaCurrentStatusString;
	private int informaCurrentStatus;

	SensorLogger<GeoSucker> _geo;
	SensorLogger<PhoneSucker> _phone;
	SensorLogger<AccelerometerSucker> _acc;

	List<BroadcastReceiver> br = new ArrayList<BroadcastReceiver>();

	public LoadingCache<Long, LogPack> suckerCache, annotationCache;
	private List<LoadingCache<Long, LogPack>> caches;
	ExecutorService ex;

	public Informa informa;
	Activity editor;
	boolean inflatedFromManifest = false;

	public Uri workingUri;
	long[] encryptList;

	String LOG = Constants.Informa.LOG;
	private int iPref;

	public interface InformaServiceListener {
		public void onInformaPackageGenerated();
	}

	public class LocalBinder extends Binder {
		public InformaService getService() {
			return InformaService.this;
		}
	}

	public void cleanup() {
		java.io.File imgTemp = new java.io.File(Storage.FileIO.DUMP_FOLDER, Storage.FileIO.IMAGE_TMP);
		if(imgTemp.exists()) {
			Log.d(Storage.LOG, "removing " + imgTemp.getAbsolutePath());
			InformaMediaScanner.doScanForDeletion(this, imgTemp);
		}

		java.io.File vidTemp = new java.io.File(Storage.FileIO.DUMP_FOLDER, Storage.FileIO.VIDEO_TMP);
		if(vidTemp.exists()) {
			Log.d(Storage.LOG, "removing " + vidTemp.getAbsolutePath());
			InformaMediaScanner.doScanForDeletion(this, vidTemp);
		}

		java.io.File vidMetadata = new java.io.File(Storage.FileIO.DUMP_FOLDER, Storage.FileIO.TMP_VIDEO_DATA_FILE_NAME);
		if(vidMetadata.exists()) {
			vidMetadata.delete();
			Log.d(Storage.LOG, "removing " + vidMetadata.getAbsolutePath());
		}

		// TODO: clean up extra if user wishes...
		switch(iPref) {
		case Settings.OriginalImageHandling.ENCRYPT_ORIGINAL:
			// move original into  
			break;
		case Settings.OriginalImageHandling.DELETE_ORIGINAL:
			// make sure image/video does not persist in ContentProvider
			// remove
			break;
		}

	}

	public void versionsCreated() {
		cleanup();
	}

	public void storeMediaCache() {
		// save original to iocipher store and cache data by dumping it to flat file that can be inflated later

		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					if(IOCipherService.getInstance().saveCache(getEventByType(CaptureEvent.METADATA_CAPTURED, annotationCache), caches)) {
						cleanup();
						suspend();
					} else
						suspend();
				} catch (JSONException e) {
					Log.e(Storage.LOG, e.toString());
					e.printStackTrace();
				} catch (InterruptedException e) {
					Log.e(Storage.LOG, e.toString());
					e.printStackTrace();
				} catch (ExecutionException e) {
					Log.e(Storage.LOG, e.toString());
					e.printStackTrace();
				}
			}

		}).start();
	}

	@SuppressWarnings("unchecked")
	private LogPack JSONObjectToLogPack(JSONObject json) throws JSONException {
		LogPack logPack = new LogPack();
		Iterator<String> jIt = json.keys();
		while(jIt.hasNext()) {
			String key = jIt.next();
			logPack.put(key, json.get(key));
		}
		return logPack;
	}

	public long[] getInitialGPSTimestamp() {
		long[] relativeTimestamps = new long[2];
		try {
			Entry<Long, LogPack> initialTimestamp = getAllEventsByTypeWithTimestamp(CaptureEvent.TIMESTAMPS_RESOLVED, annotationCache).iterator().next();
			Log.d(Storage.LOG, initialTimestamp.toString());
			relativeTimestamps[0] = initialTimestamp.getKey();
			relativeTimestamps[1] = initialTimestamp.getValue().getLong(Time.Keys.RELATIVE_TIME);
		} catch (JSONException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (InterruptedException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (ExecutionException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}
		return relativeTimestamps;
	}

	public void inflateMediaCache(String cacheFile) {
		try {
			String c = new String(IOUtility.getBytesFromFile(IOCipherService.getInstance().getFile(cacheFile)));
			JSONObject cObj = (JSONObject) new JSONTokener(c).nextValue();
			JSONArray caches = cObj.getJSONArray("cache");

			for(int i=0; i<caches.length(); i++) {
				JSONObject cache = (JSONObject) caches.get(i);
				if(cache.keys().next().equals("suckerCache")) {
					JSONArray _suckerCache = cache.getJSONArray("suckerCache");
					for(int s=0; s< _suckerCache.length(); s++) {
						JSONObject _s = _suckerCache.getJSONObject(s);
						String key = (String) _s.keys().next();
						suckerCache.put(Long.parseLong(key), JSONObjectToLogPack(_s.getJSONObject(key)));
					}
				} else if(cache.keys().next().equals("annotationCache")) {
					JSONArray _annotationCache = cache.getJSONArray("annotationCache");
					for(int s=0; s< _annotationCache.length(); s++) {
						JSONObject _s = _annotationCache.getJSONObject(s);
						String key = (String) _s.keys().next();
						annotationCache.put(Long.parseLong(key), JSONObjectToLogPack(_s.getJSONObject(key)));
					}
				}
			}

			inflatedFromManifest = true;
		} catch(JSONException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch(ClassCastException e) {
			// XXX: file was corrupted?
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}
	}

	public void setCurrentStatus(int status) {
		informaCurrentStatus = status;
		informaCurrentStatusString = getResources().getStringArray(R.array.informa_statuses)[informaCurrentStatus];
		//showNotification();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	public static InformaService getInstance() {
		return informaService;
	}

	public List<LogPack> getAllEventsByType(final int type, final LoadingCache<Long, LogPack> cache) throws InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<List<LogPack>> query = ex.submit(new Callable<List<LogPack>>() {

			@Override
			public List<LogPack> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				List<LogPack> events = new ArrayList<LogPack>();
				while(cIt.hasNext()) {
					Entry<Long, LogPack> entry = cIt.next();
					if(entry.getValue().has(CaptureEvent.Keys.TYPE) && entry.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						events.add(entry.getValue());
				}

				return events;
			}
		});

		List<LogPack> events = query.get();
		ex.shutdown();

		return events;
	}

	public List<Entry<Long, LogPack>> getAllEventsByTypeWithTimestamp(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<List<Entry<Long, LogPack>>> query = ex.submit(new Callable<List<Entry<Long, LogPack>>>() {

			@Override
			public List<Entry<Long, LogPack>> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				List<Entry<Long, LogPack>> events = new ArrayList<Entry<Long, LogPack>>();
				while(cIt.hasNext()) {
					Entry<Long, LogPack> entry = cIt.next();
					if(entry.getValue().has(CaptureEvent.Keys.TYPE) && entry.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						events.add(entry);
				}

				return events;
			}
		});

		List<Entry<Long, LogPack>> events = query.get();
		ex.shutdown();

		return events;
	}

	public Entry<Long, LogPack> getEventByTypeWithTimestamp(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<Entry<Long, LogPack>> query = ex.submit(new Callable<Entry<Long, LogPack>>() {

			@Override
			public Entry<Long, LogPack> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				Entry<Long, LogPack> entry = null;
				while(cIt.hasNext() && entry == null) {
					Entry<Long, LogPack> e = cIt.next();
					if(e.getValue().has(CaptureEvent.Keys.TYPE) && e.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						entry = e;
				}

				return entry;
			}
		});

		Entry<Long, LogPack> entry = query.get();
		ex.shutdown();

		return entry;
	}

	public LogPack getEventByType(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<LogPack> query = ex.submit(new Callable<LogPack>() {

			@Override
			public LogPack call() throws Exception {
				Iterator<LogPack> cIt = cache.asMap().values().iterator();
				LogPack logPack = null;
				while(cIt.hasNext() && logPack == null) {
					LogPack lp = cIt.next();

					if(lp.has(CaptureEvent.Keys.TYPE) && lp.getInt(CaptureEvent.Keys.TYPE) == type)
						logPack = lp;
				}

				return logPack;
			}

		});
		LogPack logPack = query.get();
		ex.shutdown();

		return logPack;
	}

	public int getStatus() {
		return informaCurrentStatus;
	}

	@Override
	public void onCreate() {
		Log.d(Constants.Informa.LOG, "InformaService running");

		toMainActivity = new Intent(this, MainActivity.class);
		toMainActivity.putExtra(App.InformaService.Keys.FROM_NOTIFICATION, App.InformaService.Notifications.INIT);
		nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		br.add(new Broadcaster(new IntentFilter(BluetoothDevice.ACTION_FOUND)));
		br.add(new Broadcaster(new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)));

		for(BroadcastReceiver b : br)
			registerReceiver(b, ((Broadcaster) b).intentFilter);

		iPref = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString(Settings.Keys.DEFAULT_IMAGE_HANDLING, null));
		setCurrentStatus(Status.STOPPED);

		informaService = this;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(LOG, "InformaService stopped");
		for(BroadcastReceiver b : br)
			unregisterReceiver(b);
	}

	@SuppressWarnings("unchecked")
	public void init() {
		initCaches();

		_geo = new GeoSucker(InformaService.this);
		_phone = new PhoneSucker(InformaService.this);
		_acc = new AccelerometerSucker(InformaService.this);
		setCurrentStatus(Status.RUNNING);
	}

	private void initCaches() {
		caches = new ArrayList<LoadingCache<Long, LogPack>>();

		if(suckerCache != null)
			suckerCache = null;

		suckerCache = CacheBuilder.newBuilder()
				.build(new CacheLoader<Long, LogPack>() {
					@Override
					public LogPack load(Long timestamp) throws Exception {
						return suckerCache.getUnchecked(timestamp);
					}
				});
		caches.add(suckerCache);

		if(annotationCache != null)
			annotationCache = null;

		annotationCache = CacheBuilder.newBuilder()
				.build(new CacheLoader<Long, LogPack>() {
					@Override
					public LogPack load(Long timestamp) throws Exception {
						return annotationCache.getUnchecked(timestamp);
					}
				});
		caches.add(annotationCache);
	}

	public void suspend() {
		this.setCurrentStatus(Status.STOPPED);
		try {
			_geo.getSucker().stopUpdates();
			_phone.getSucker().stopUpdates();
			_acc.getSucker().stopUpdates();
		} catch(NullPointerException e) {
			e.printStackTrace();
		}
		_geo = null;
		_phone = null;
		_acc = null;
	}

	@SuppressWarnings("unused")
	private void doShutdown() {
		suspend();

		for(BroadcastReceiver b : br)
			unregisterReceiver(b);

		stopSelf();
	}

	@SuppressWarnings("deprecation")
	public void showNotification() {
		Notification n = new Notification(
				R.drawable.ic_launcher_ssc,
				getString(R.string.app_name),
				System.currentTimeMillis());

		n.contentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.informa_notification);

		PendingIntent pi = PendingIntent.getActivity(
				this,
				Constants.Informa.FROM_NOTIFICATION_BAR, 
				toMainActivity,
				PendingIntent.FLAG_UPDATE_CURRENT);

		n.setLatestEventInfo(this, getString(R.string.app_name), informaCurrentStatusString, pi);
		nm.notify(R.string.app_name_lc, n);
	}

	@SuppressWarnings("unused")
	private void pushToSucker(SensorLogger<?> sucker, LogPack logPack) throws JSONException {
		if(sucker.getClass().equals(PhoneSucker.class))
			_phone.sendToBuffer(logPack);
	}

	public long getCurrentTime() {
		try {
			return ((GeoSucker) _geo).getTime();
		} catch(NullPointerException e) {
			return 0;
		}
	}

	public void onUpdate(LogPack logPack) {
		try {
			long ts = ((GeoSucker) _geo).getTime();
			onUpdate(ts, logPack);
		} catch(NullPointerException e) {

		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onUpdate(long timestamp, final LogPack logPack) {

		try {
			LogPack lp = null;
			switch(logPack.getInt(CaptureEvent.Keys.TYPE)) {
			case CaptureEvent.SENSOR_PLAYBACK:
				lp = suckerCache.getIfPresent(timestamp);
				if(lp != null) {
					//Log.d(Suckers.LOG, "already have " + timestamp + " :\n" + lp.toString());
					Iterator<String> lIt = lp.keys();
					while(lIt.hasNext()) {
						String key = lIt.next();
						logPack.put(key, lp.get(key));	
					}
				}

				suckerCache.put(timestamp, logPack);
				break;
			default:
				lp = annotationCache.getIfPresent(timestamp);

				if(lp != null) {
					Iterator<String> lIt = lp.keys();
					while(lIt.hasNext()) {
						String key = lIt.next();
						logPack.put(key, lp.get(key));

					}
				}

				annotationCache.put(timestamp, logPack);
				break;
			}

		} catch (JSONException e) {}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onInformaInitForExport(final Activity a, final String clonePath, info.guardianproject.iocipher.File cache, final int mediaType) {
		initCaches();
		inflateMediaCache(cache.getAbsolutePath());

		_phone = new PhoneSucker(InformaService.this);
		informa = new Informa();
		try {
			LogPack originalData = getMetadata();
			informa.setDeviceCredentials(_phone.getSucker().forceReturn());
			_phone.getSucker().stopUpdates();
			_phone = null;

			informa.setFileInformation(originalData.getString(Genealogy.LOCAL_MEDIA_PATH));
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						if(informa.setInitialData(getEventByTypeWithTimestamp(CaptureEvent.METADATA_CAPTURED, annotationCache))) {
							if(informa.addToPlayback(getAllEventsByTypeWithTimestamp(CaptureEvent.SENSOR_PLAYBACK, suckerCache))) {
								if(mediaType == Media.Type.IMAGE) {
									@SuppressWarnings("unused")
									ImageConstructor imageConstructor = new ImageConstructor(a, annotationCache, clonePath);
								} else if(mediaType == Media.Type.VIDEO) {
									try {
										VideoConstructor vc = new VideoConstructor(a);
										vc.buildInformaVideo(a, annotationCache, clonePath);
									} catch(NullPointerException e) {
										Log.e(Storage.LOG, e.toString());
										e.printStackTrace();
									} catch (FileNotFoundException e) {
										Log.e(Storage.LOG, e.toString());
										e.printStackTrace();
									} catch (IOException e) {
										Log.e(Storage.LOG, e.toString());
										e.printStackTrace();
									}

								}
							}
						}
					}catch(JSONException e) {
						Log.e(Storage.LOG, e.toString());
						e.printStackTrace();
					} catch (InterruptedException e) {
						Log.e(Storage.LOG, e.toString());
						e.printStackTrace();
					} catch (ExecutionException e) {
						Log.e(Storage.LOG, e.toString());
						e.printStackTrace();
					}

				}
			}).start();

		} catch(JSONException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (InterruptedException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (ExecutionException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}
	}

	@Override
	public void onInformaInit(Activity editor, Uri workingUri) {
		informa = new Informa();
		try {
			LogPack originalData = getMetadata();
			
			informa.setFileInformation(originalData.getString(Genealogy.LOCAL_MEDIA_PATH));
		} catch(JSONException e) {}
		catch(NullPointerException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
			// TODO: this log has been corrupted.
		} catch (InterruptedException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (ExecutionException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}

		try {
			informa.setDeviceCredentials(_phone.getSucker().forceReturn());
		} catch (JSONException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}
		
		this.editor = editor;
		this.workingUri = workingUri;
	}

	public void packageInforma(final String originalImagePath) {
		// TODO: if user wishes, add image/video to gallery also
		if(iPref == Settings.OriginalImageHandling.LEAVE_ORIGINAL_ALONE) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					// TODO: GPS TIME PLS
					SimpleDateFormat dateFormat = new SimpleDateFormat(Constants.Media.DateFormats.EXPORT_DATE_FORMAT);
					Date date = new Date();
					String dateString = dateFormat.format(date);

					ContentValues cv = new ContentValues();
					cv.put(Images.Media.DATE_ADDED, dateString);
					cv.put(Images.Media.DATE_TAKEN, dateString);
					cv.put(Images.Media.DATE_MODIFIED, dateString);
					cv.put(Images.Media.DESCRIPTION, Exif.DESCRIPTION);
					cv.put(Images.Media.TITLE, Exif.TITLE);

					Uri persistUri = getContentResolver().insert(Images.Media.EXTERNAL_CONTENT_URI, cv);
					try {
						OutputStream os = getContentResolver().openOutputStream(persistUri);
						FileInputStream fis = new FileInputStream(new java.io.File(originalImagePath));
						byte[] fBytes = new byte[fis.available()];
						fis.read(fBytes);
						fis.close();

						os.write(fBytes);
						os.flush();
						os.close();

					} catch (FileNotFoundException e) {
						Log.e(Storage.LOG, e.toString());
						e.printStackTrace();
					} catch (IOException e) {
						Log.e(Storage.LOG, e.toString());
						e.printStackTrace();
					}

					// add media to ContentProvider
				}
			}).start();
		}

		Thread packageInforma = new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							if(informa.setInitialData(getEventByTypeWithTimestamp(CaptureEvent.METADATA_CAPTURED, annotationCache)))
								if(informa.addToPlayback(getAllEventsByTypeWithTimestamp(CaptureEvent.SENSOR_PLAYBACK, suckerCache))) {
									((InformaServiceListener) editor).onInformaPackageGenerated();

									if(editor.getLocalClassName().equals(App.ImageEditor.TAG)) {
										@SuppressWarnings("unused")
										ImageConstructor imageConstructor = new ImageConstructor(InformaService.this.getApplicationContext(), annotationCache, encryptList, originalImagePath);
									} else if(editor.getLocalClassName().equals(App.VideoEditor.TAG)) {
										VideoConstructor.getVideoConstructor().buildInformaVideo(InformaService.this.getApplicationContext(), annotationCache, encryptList);
									}

								}
						} catch(JSONException e){}
						catch (InterruptedException e) {
							e.printStackTrace();
						} catch (ExecutionException e) {
							e.printStackTrace();
						}

						suspend();
					}
				});
		packageInforma.start();
	}

	public LogPack getMetadata() throws JSONException, InterruptedException, ExecutionException {
		return getEventByType(CaptureEvent.METADATA_CAPTURED, annotationCache);
	}

	public void setEncryptionList(long[] encryptList) {
		this.encryptList = encryptList;
	}

	@SuppressWarnings("unchecked")
	private void changeRegion(JSONObject rep) {
		try {
			long timestamp = 0L;

			try {
				timestamp = (Long) rep.get(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);
			} catch(ClassCastException e) {
				timestamp = Long.parseLong((String) rep.get(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP));
			}

			rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);

			try {
				LogPack logPack = annotationCache.getIfPresent(timestamp);
				Iterator<String> repIt = rep.keys();
				while(repIt.hasNext()) {
					String key = repIt.next();
					logPack.put(key, rep.get(key));
				}

				//onUpdate(timestamp, logPack);
			} catch(IllegalStateException e) {
				Log.e(Storage.LOG, "recursive load?\n" + e.toString());
				e.printStackTrace();
			} catch(NullPointerException e) {
				Log.e(Storage.LOG, e.toString());
				e.printStackTrace();
			}


		} catch(JSONException e) {}
	}

	@SuppressWarnings("unchecked")
	private void removeRegion(JSONObject rep) {
		long timestamp = 0L;

		try {
			timestamp = (Long) rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);
		} catch(ClassCastException e) {
			timestamp = Long.parseLong((String) rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP));
		}

		try { 
			LogPack logPack = annotationCache.getIfPresent(timestamp);

			Iterator<String> repIt = rep.keys();
			while(repIt.hasNext())
				logPack.remove(repIt.next());
		} catch(NullPointerException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private void addRegion(JSONObject rep) {
		try {
			long timestamp = (Long) rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);

			LogPack logPack = new LogPack(CaptureEvent.Keys.TYPE, CaptureEvent.REGION_GENERATED);
			Iterator<String> repIt = rep.keys();
			while(repIt.hasNext()) {
				String key = repIt.next();
				logPack.put(key, rep.get(key));
			}

			onUpdate(timestamp, logPack);
		} catch(JSONException e) {}
	}

	public List<LogPack> getCachedRegions() {
		List<LogPack> cachedRegions = null;

		try {
			for(Entry<Long, LogPack> entry : getAllEventsByTypeWithTimestamp(CaptureEvent.REGION_GENERATED, annotationCache)) {
				if(cachedRegions == null)
					cachedRegions = new ArrayList<LogPack>();

				LogPack lp = entry.getValue();
				lp.put(Data.ImageRegion.TIMESTAMP, entry.getKey());

				cachedRegions.add(lp);
			}

		} catch (InterruptedException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (ExecutionException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		} catch (JSONException e) {
			Log.e(Storage.LOG, e.toString());
			e.printStackTrace();
		}

		return cachedRegions;
	}

	@Override
	public void onImageRegionCreated(final ImageRegion ir) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							addRegion(ir.getRepresentation());
						} catch(JSONException e) {
							Log.e(LOG, e.toString());
							e.printStackTrace();
						}
					}
				}).start();

	}

	@Override
	public void onImageRegionChanged(final ImageRegion ir) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							changeRegion(ir.getRepresentation());
							Log.d(LOG, ir.getRepresentation().toString());
						} catch (JSONException e) {
							Log.e(LOG, e.toString());
							e.printStackTrace();
						}
					}
				}).start();
	}

	@Override
	public void onImageRegionRemoved(final ImageRegion ir) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							removeRegion(ir.getRepresentation());
						} catch(JSONException e) {
							Log.e(LOG, e.toString());
							e.printStackTrace();	
						}
					}
				}).start();

	}

	@Override
	public void onVideoRegionCreated(final RegionTrail rt) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							addRegion(rt.getRepresentation());
						} catch(JSONException e) {
							Log.e(LOG, e.toString());
						}
					}

				}).start();

	}

	@Override
	public void onVideoRegionChanged(final RegionTrail rt) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							changeRegion(rt.getRepresentation());
						} catch(JSONException e) {
							Log.e(LOG, e.toString());
						}
					}

				}).start();

	}

	@Override
	public void onVideoRegionDeleted(final RegionTrail rt) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						try {
							removeRegion(rt.getRepresentation());
						} catch(JSONException e) {
							Log.e(LOG, e.toString());
						}
					}

				}).start();

	}

	private class Broadcaster extends BroadcastReceiver {
		IntentFilter intentFilter;

		public Broadcaster(IntentFilter intentFilter) {
			this.intentFilter = intentFilter;
		}

		@Override
		public void onReceive(Context c, Intent i) {
			if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
				if(InformaService.this.getStatus() == Status.RUNNING) {
					try {
						BluetoothDevice bd = (BluetoothDevice) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
						LogPack logPack = new LogPack(Phone.Keys.BLUETOOTH_DEVICE_ADDRESS, bd.getAddress());
						logPack.put(Phone.Keys.BLUETOOTH_DEVICE_NAME, bd.getName());

						suckerCache.put(((GeoSucker) _geo).getTime(), logPack);
					} catch(JSONException e) {}
				}
			}

			if(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(i.getAction())) {
				if(InformaService.this.getStatus() == Status.RUNNING) {
					LogPack logPack = new LogPack(Phone.Keys.VISIBLE_WIFI_NETWORKS, ((PhoneSucker) _phone).getWifiNetworks());
					suckerCache.put(((GeoSucker) _geo).getTime(), logPack);
				}
			}

		}
	}
}