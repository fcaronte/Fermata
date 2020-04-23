package me.aap.fermata.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.view.MediaItemListView;
import me.aap.fermata.ui.view.MediaItemListViewAdapter;
import me.aap.fermata.ui.view.MediaItemView;
import me.aap.fermata.ui.view.MediaItemWrapper;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.ui.view.ToolBarView;

import static me.aap.utils.misc.Assert.assertTrue;

/**
 * @author Andrey Pavlenko
 */
public abstract class MediaLibFragment extends MainActivityFragment implements MainActivityListener,
		PreferenceStore.Listener, FermataServiceUiBinder.Listener {
	private ListAdapter adapter;
	private MediaItemListView listView;
	private int scrollPosition;

	abstract ListAdapter createAdapter(FermataServiceUiBinder b);

	public abstract CharSequence getFragmentTitle();

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarMediator.instance;
	}

	@Override
	public FloatingButton.Mediator getFloatingButtonMediator() {
		return FloatingButtonMediator.instance;
	}

	@Override
	public CharSequence getTitle() {
		ListAdapter adapter = getAdapter();
		if (adapter == null) return getFragmentTitle();

		BrowsableItem parent = adapter.getParent();
		if ((parent != null) && (parent.getParent() != null)) return parent.getName();
		else return getFragmentTitle();
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setRetainInstance(true);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.media_items_list_view, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		listView = view.findViewById(R.id.media_items_list_view);
		listView.setHasFixedSize(true);
		listView.setLayoutManager(new LinearLayoutManager(getContext()));

		if (adapter != null) {
			attachTouchHelper();
		} else {
			MainActivityDelegate a = getMainActivity();
			FermataServiceUiBinder b = a.getMediaServiceBinder();

			if (b != null) {
				bind(b);
				a.addBroadcastListener(this, FILTER_CHANGED | ACTIVITY_FINISH);
			} else {
				a.addBroadcastListener(this, SERVICE_BOUND | FILTER_CHANGED | ACTIVITY_FINISH);
			}
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		listView = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		MainActivityDelegate a = getMainActivity();
		if (a == null) return;

		a.removeBroadcastListener(this);
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		if (b == null) return;

		b.removeBroadcastListener(this);
		a.getPrefs().removeBroadcastListener(this);
		b.getLib().getPrefs().removeBroadcastListener(this);
	}

	private void bind(FermataServiceUiBinder b) {
		assertTrue(adapter == null);
		adapter = createAdapter(b);
		b.addBroadcastListener(this);
		b.getLib().getPrefs().addBroadcastListener(this);
		getMainActivity().getPrefs().addBroadcastListener(this);
		attachTouchHelper();
	}

	private void attachTouchHelper() {
		assertTrue(adapter != null);
		assertTrue(listView != null);
		adapter.setListView(listView);
		listView.setAdapter(adapter);
		ItemTouchHelper touchHelper = new ItemTouchHelper(adapter.getItemTouchCallback());
		touchHelper.attachToRecyclerView(listView);
	}

	public MainActivityDelegate getMainActivity() {
		return MainActivityDelegate.get(getContext());
	}

	public MediaLib getLib() {
		return getMainActivity().getLib();
	}

	@Override
	public boolean isRootPage() {
		ListAdapter a = getAdapter();
		if (a == null) return true;

		BrowsableItem p = a.getParent();
		return (p == null) || (p.getParent() == null);
	}

	public void revealItem(Item i) {
		ListAdapter a = getAdapter();
		BrowsableItem p = i.getParent();
		if (p == null) return;

		if (!p.equals(a.getParent())) a.setParent(p);

		// Make sure the list is loaded
		p.getChildren().withMainHandler().onSuccess(l -> {
			scrollPosition = indexOf(a.getList(), i);
			FermataApplication.get().getHandler().post(this::scrollToPosition);
		});
	}

	public boolean onBackPressed() {
		ListAdapter a = getAdapter();
		BrowsableItem p = a.getParent();
		if (p == null) return false;
		p = p.getParent();
		if (p == null) return false;
		a.setParent(p);
		return true;
	}

	public void reload() {
		discardSelection();
		getAdapter().reload();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) scrollToPosition();
	}

	@Override
	public void onPlayableChanged(PlayableItem oldItem, PlayableItem newItem) {
		scrollPosition = -1;
		MediaItemListView view = getListView();
		if (view == null) return;

		ListAdapter a = getAdapter();
		BrowsableItem p = a.getParent();
		if (p == null) return;
		List<MediaItemWrapper> list = a.getList();

		if ((oldItem != null) && p.equals(oldItem.getParent())) {
			if ((newItem != null) && isSupportedItem(newItem)) {
				BrowsableItem newParent = newItem.getParent();

				if (p.equals(newParent)) {
					scrollPosition = indexOf(list, newItem);
				} else {
					a.setParent(newParent);
					scrollPosition = indexOf(a.getList(), newItem);
				}
			} else {
				scrollPosition = indexOf(list, oldItem);
			}
		}

		if (!isHidden()) {
			scrollToPosition();
			a.getListView().refreshState();
		}
	}

	@Override
	public void durationChanged(PlayableItem i) {
		ListAdapter a = getAdapter();
		if (a == null) return;

		for (MediaItemWrapper w : a.getList()) {
			if (i.equals(w.getItem())) {
				MediaItemView v = w.getView();
				if (v != null) v.refresh();
				break;
			}
		}
	}

	boolean isSupportedItem(Item i) {
		return false;
	}

	@SuppressWarnings("unchecked")
	public <A extends ListAdapter> A getAdapter() {
		return (A) adapter;
	}

	public MediaItemListView getListView() {
		return listView;
	}

	public void discardSelection() {
		getAdapter().getListView().discardSelection();
	}

	private void scrollToPosition() {
		FermataApplication.get().getHandler().post(() -> {
			int pos = scrollPosition;
			if (pos == -1) return;
			MediaItemListView list = getListView();
			if (list == null) return;
			list.smoothScrollToPosition(pos);
		});
	}

	private static final Set<PreferenceStore.Pref<?>> reloadOnPrefChange = new HashSet<>(Arrays.asList(
			BrowsableItemPrefs.TITLE_SEQ_NUM,
			BrowsableItemPrefs.TITLE_NAME,
			BrowsableItemPrefs.TITLE_FILE_NAME,
			BrowsableItemPrefs.SUBTITLE_NAME,
			BrowsableItemPrefs.SUBTITLE_FILE_NAME,
			BrowsableItemPrefs.SUBTITLE_ALBUM,
			BrowsableItemPrefs.SUBTITLE_ARTIST,
			BrowsableItemPrefs.SUBTITLE_DURATION,
			BrowsableItemPrefs.SORT_BY
	));

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (!handleActivityFinishEvent(a, e)) {
			if (e == FILTER_CHANGED) {
				if (adapter != null) {
					EditText t = a.getToolBar().findViewById(R.id.tool_bar_filter);
					adapter.setFilter(t.getText().toString());
				}
			} else if (e == SERVICE_BOUND) {
				bind(a.getMediaServiceBinder());
			}
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		BrowsableItem p = getAdapter().getParent();
		if ((p == null) || !store.equals(p.getPrefs())) return;
		if (!Collections.disjoint(reloadOnPrefChange, prefs)) getAdapter().reload();
	}

	public class ListAdapter extends MediaItemListViewAdapter {

		ListAdapter(BrowsableItem parent) {
			super.setParent(parent);
		}

		@Override
		public void setParent(BrowsableItem parent) {
			BrowsableItem prev = super.getParent();
			super.setParent(parent);

			if (!isHidden()) {
				if (parent != null) {
					int idx = indexOf(getList(), prev);

					if (idx != -1) {
						scrollPosition = idx;
						scrollToPosition();
					}
				}

				getMainActivity().fireBroadcastEvent(FRAGMENT_CONTENT_CHANGED);
			}
		}

		@Override
		public void onClick(View v) {
			Item i = ((MediaItemView) v).getItem();
			discardSelection();

			if (i instanceof PlayableItem) {
				PlayableItem pi = (PlayableItem) i;
				MainActivityDelegate a = getMainActivity();
				a.getMediaServiceBinder().playItem((PlayableItem) i);
				if (pi.isVideo()) a.showFragment(R.id.video);
			} else {
				super.onClick(v);
			}
		}

		@Override
		protected void setChildren(List<? extends Item> children) {
			super.setChildren(children);
			BrowsableItem p = getParent();
			PlayableItem current = getMainActivity().getCurrentPlayable();

			if ((current != null) && current.getParent().equals(p)) {
				scrollPosition = indexOf(getList(), current);
				if (!isHidden()) scrollToPosition();
			} else {
				p.getLastPlayedItem().withMainHandler().onSuccess(last -> {
					scrollPosition = (last != null) ? indexOf(getList(), last) : -1;
					if (!isHidden()) scrollToPosition();
				});
			}
		}
	}

	private static int indexOf(List<MediaItemWrapper> list, Item item) {
		int size = list.size();
		for (int i = 0; i < size; i++) {
			if (item.equals(list.get(i).getItem())) return i;
		}
		return -1;
	}
}
