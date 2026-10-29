package il.mkpromises;

import android.view.View;

/** One page of the app. build() is called again whenever the data it shows changes. */
interface Screen {
    String title();

    View build(MainActivity app);
}
