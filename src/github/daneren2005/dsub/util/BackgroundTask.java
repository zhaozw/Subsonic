/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package github.daneren2005.dsub.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.xmlpull.v1.XmlPullParserException;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import github.daneren2005.dsub.R;
import github.daneren2005.dsub.view.ErrorDialog;

/**
 * @author Sindre Mehus
 */
public abstract class BackgroundTask<T> implements ProgressListener {
    private static final String TAG = BackgroundTask.class.getSimpleName();

    private final Context context;
	protected boolean cancelled = false;
	protected OnCancelListener cancelListener;
	protected Task task;

	private static final int DEFAULT_CONCURRENCY = 5;
	private static final Collection<Thread> threads = Collections.synchronizedCollection(new ArrayList<Thread>());
	protected static final BlockingQueue<BackgroundTask.Task> queue = new LinkedBlockingQueue<BackgroundTask.Task>(10);
	private static Handler handler = null;
	static {
		try {
			handler = new Handler(Looper.getMainLooper());
		} catch(Exception e) {
			// Not called from main thread
		}
	}

    public BackgroundTask(Context context) {
        this.context = context;

		if(threads.size() < DEFAULT_CONCURRENCY) {
			for(int i = threads.size(); i < DEFAULT_CONCURRENCY; i++) {
				Thread thread = new Thread(new TaskRunnable(), String.format("BackgroundTask_%d", i));
				threads.add(thread);
				thread.start();
			}
		}
		if(handler == null) {
			try {
				handler = new Handler(Looper.getMainLooper());
			} catch(Exception e) {
				// Not called from main thread
			}
		}
    }

	public static void stopThreads() {
		for(Thread thread: threads) {
			thread.interrupt();
		}
		threads.clear();
		queue.clear();
	}

    protected Activity getActivity() {
        return (context instanceof Activity) ? ((Activity) context) : null;
    }

    protected Handler getHandler() {
        return handler;
    }

	public abstract void execute();

    protected abstract T doInBackground() throws Throwable;

    protected abstract void done(T result);

    protected void error(Throwable error) {
        Log.w(TAG, "Got exception: " + error, error);
		Activity activity = getActivity();
		if(activity != null) {
        	new ErrorDialog(activity, getErrorMessage(error), true);
		}
    }

    protected String getErrorMessage(Throwable error) {

        if (error instanceof IOException && !Util.isNetworkConnected(context)) {
            return context.getResources().getString(R.string.background_task_no_network);
        }

        if (error instanceof FileNotFoundException) {
            return context.getResources().getString(R.string.background_task_not_found);
        }

        if (error instanceof IOException) {
            return context.getResources().getString(R.string.background_task_network_error);
        }

        if (error instanceof XmlPullParserException) {
            return context.getResources().getString(R.string.background_task_parse_error);
        }

        String message = error.getMessage();
        if (message != null) {
            return message;
        }
        return error.getClass().getSimpleName();
    }

	public void cancel() {
		cancelled = true;
		if(task != null) {
			task.cancel();
		}
		if(cancelListener != null) {
			cancelListener.onCancel();
		}
	}
	public boolean isCancelled() {
		return cancelled;
	}
	public void setOnCancelListener(OnCancelListener listener) {
		cancelListener = listener;
	}

	public boolean isRunning() {
		if(task == null) {
			return false;
		} else {
			return task.isRunning();
		}
	}

    @Override
    public abstract void updateProgress(final String message);

    @Override
    public void updateProgress(int messageId) {
        updateProgress(context.getResources().getString(messageId));
    }

	protected class Task {
		private Thread thread;
		private AtomicBoolean taskStart = new AtomicBoolean(true);

		private void execute() throws Exception {
			if(!taskStart.get()) {
				return;
			}

			thread = Thread.currentThread();
			try {
				final T result = doInBackground();
				if(isCancelled()) {
					return;
				}

				if(handler != null) {
					handler.post(new Runnable() {
						@Override
						public void run() {
							onDone(result);
							taskStart.set(false);
						}
					});
				} else {
					taskStart.set(false);
				}
			} catch(InterruptedException interrupt) {
				if(taskStart.get()) {
					// Don't exit root thread if task cancelled
					throw interrupt;
				}
			} catch(final Throwable t) {
				if(isCancelled()) {
					return;
				}

				if(handler != null) {
					handler.post(new Runnable() {
						@Override
						public void run() {
							try {
								onError(t);
								taskStart.set(false);
							} catch(Exception e) {
								// Don't care
							}
						}
					});
				} else {
					taskStart.set(false);
				}
			}
		}

		public void cancel() {
			taskStart.set(false);
			if(thread != null) {
				thread.interrupt();
			}
		}
		public void onDone(T result) {
			done(result);
		}
		public void onError(Throwable t) {
			error(t);
		}

		public boolean isRunning() {
			return taskStart.get();
		}
	}

	private class TaskRunnable implements Runnable {
		private boolean running = true;

		public TaskRunnable() {

		}

		@Override
		public void run() {
			Looper.prepare();
			while(running) {
				try {
					Task task = queue.take();
					task.execute();
				} catch(InterruptedException stop) {
					running = false;
					threads.remove(Thread.currentThread());
				} catch(Throwable t) {
					Log.e(TAG, "Unexpected crash in BackgroundTask thread", t);
				}
			}
		}
	}

	public static interface OnCancelListener {
		void onCancel();
	}
}
