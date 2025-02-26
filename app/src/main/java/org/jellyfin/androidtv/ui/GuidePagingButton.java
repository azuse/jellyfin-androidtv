package org.jellyfin.androidtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.RelativeLayout;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.databinding.ProgramGridCellBinding;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideActivity;
import org.jellyfin.androidtv.util.Utils;

public class GuidePagingButton extends RelativeLayout {

    public GuidePagingButton(Context context) {
        super(context);
    }

    public GuidePagingButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GuidePagingButton(final Activity activity, final LiveTvGuide guide, int start, String label) {
        super(activity);

        LayoutInflater inflater = LayoutInflater.from(activity);
        ProgramGridCellBinding binding = ProgramGridCellBinding.inflate(inflater, this, true);
        binding.programName.setText(label);

        setBackgroundColor(Utils.getThemeColor(activity, R.attr.buttonDefaultNormalBackground));
        setFocusable(true);
        setOnClickListener(v -> guide.displayChannels(start, LiveTvGuideActivity.PAGE_SIZE));
    }

    @Override
    protected void onFocusChanged(boolean hasFocus, int direction, Rect previouslyFocused) {
        super.onFocusChanged(hasFocus, direction, previouslyFocused);

        setBackgroundColor(Utils.getThemeColor(getContext(),
            hasFocus ? android.R.attr.colorAccent : R.attr.buttonDefaultNormalBackground));
    }
}
