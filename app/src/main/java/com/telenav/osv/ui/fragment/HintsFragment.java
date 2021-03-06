package com.telenav.osv.ui.fragment;

import java.util.ArrayList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.matthewtamlin.dotindicator.DotIndicator;
import com.telenav.osv.R;
import com.telenav.osv.activity.MainActivity;

/**
 * Created by Kalman on 18/07/16.
 */
public class HintsFragment extends OSVFragment {

    public static final String TAG = "HintsFragment";

    private ViewPager hintPager;

    private DotIndicator hintIndicator;

    private HintPagerAdapter hintPagerAdapter;

    private Runnable hintPagerAutoRunnable;

    private MainActivity activity;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_recording_hints, null);
        activity = (MainActivity) getActivity();
        hintPager = view.findViewById(R.id.hint_pager);
        hintIndicator = view.findViewById(R.id.hint_indicator);
        hintPagerAdapter = new HintPagerAdapter(inflater);
        int orientation = activity.getResources().getConfiguration().orientation;
        boolean portrait = orientation == Configuration.ORIENTATION_PORTRAIT;
        hintPagerAdapter.populate(portrait);
        hintPager.setAdapter(hintPagerAdapter);
        hintPagerAutoRunnable = () -> hintPager.setCurrentItem((hintPager.getCurrentItem() + 1) % hintPagerAdapter.getCount(), true);
        hintPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                hintIndicator.setSelectedItem(Math.min(position, hintIndicator.getNumberOfItems() - 1), true);
                hintPager.removeCallbacks(hintPagerAutoRunnable);
                hintPager.postDelayed(hintPagerAutoRunnable, 8000);
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
        hintPager.postDelayed(hintPagerAutoRunnable, 8000);
        ImageView mCloseButton = view.findViewById(R.id.close_button);
        mCloseButton.setOnClickListener(v -> activity.onBackPressed());
        return view;
    }

    @Override
    public void cancelAction() {

    }

    @Override
    public boolean onBackPressed() {
        return false;
    }

    @Override
    public View getSharedElement() {
        return null;
    }

    @Override
    public String getSharedElementTransitionName() {
        return null;
    }

    @Override
    public int getEnterAnimation() {
        return R.anim.alpha_add;
    }

    @Override
    public int getExitAnimation() {
        return R.anim.alpha_remove;
    }

    public class HintPagerAdapter extends PagerAdapter {

        private final LayoutInflater mInflater;

        /**
         * The fragments used in the pager
         */
        ArrayList<LinearLayout> views = new ArrayList<>();

        ArrayList<Integer> colors = new ArrayList<>();

        ArrayList<String[]> hints = new ArrayList<>();

        HintPagerAdapter(LayoutInflater inflater) {
            super();
            mInflater = inflater;
            hints.clear();

            String[] secondHint = new String[2];
            String[] thirdHint = new String[2];
            String[] fourthHint = new String[2];
            String[] fifthHint = new String[2];

            secondHint[0] = getString(R.string.hint_mount_label);
            secondHint[1] = getString(R.string.hint_mount_message);
            thirdHint[0] = getString(R.string.hint_windshield_label);
            thirdHint[1] = getString(R.string.hint_windshield_message);
            fourthHint[0] = getString(R.string.hint_focus_label);
            fourthHint[1] = getString(R.string.hint_focus_message);
            fifthHint[0] = getString(R.string.hint_points);
            fifthHint[1] = getString(R.string.hint_points_message);

            hints.add(fifthHint);
            hints.add(secondHint);
            hints.add(thirdHint);
            hints.add(fourthHint);
            colors.clear();
            colors.add(R.color.hint_blue);
            colors.add(R.color.hint_purple);
            colors.add(R.color.hint_green);
            colors.add(R.color.hint_yellow);
            colors.add(R.color.hint_red);
        }

        @Override
        public int getCount() {
            return views.size();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            View v = views.get(Math.min(position, views.size() - 1));
            container.addView(v);
            return v;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(Object object) {
            return POSITION_NONE;
        }

        void populate(boolean portrait) {
            views.clear();
            TextView viewTitleHint;
            TextView viewHintDescription;
            LinearLayout layout;
            LinearLayout landscape = null;
            int numberOfItems;
            if (portrait) {
                landscape = (LinearLayout) mInflater.inflate(R.layout.item_hint_text, null);
                landscape.setBackgroundColor(activity.getResources().getColor(colors.get((views.size() + 1) % colors.size())));
                viewTitleHint = landscape.findViewById(R.id.title_hint_text_vertical);
                viewTitleHint.setText(R.string.hint_landscape_label);
                viewHintDescription = landscape.findViewById(R.id.hint_text_vertical);
                viewHintDescription.setText(R.string.hint_landscape_message);
                numberOfItems = hints.size() + 1;
            } else {
                numberOfItems = hints.size();
            }
            int i = 0;
            for (String[] hint : hints) {
                if (i == 1 && landscape != null) {
                    views.add(landscape);
                }
                layout = (LinearLayout) mInflater.inflate(R.layout.item_hint_text, null);
                layout.setBackgroundColor(activity.getResources().getColor(colors.get(views.size() % colors.size())));
                viewTitleHint = layout.findViewById(R.id.title_hint_text_vertical);
                viewHintDescription = layout.findViewById(R.id.hint_text_vertical);
                viewTitleHint.setText(hint[0]);
                viewHintDescription.setText(hint[1]);
                views.add(layout);
                i++;
            }
            hintIndicator.setNumberOfItems(numberOfItems);
            notifyDataSetChanged();
        }
    }
}
