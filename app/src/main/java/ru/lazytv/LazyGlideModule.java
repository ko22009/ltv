package ru.lazytv;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

/** Keeps the image cache small on a TV: 64 MB on disk and 16 MB in RAM instead of the large defaults. */
@GlideModule
public final class LazyGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, "image_cache", 64L * 1024L * 1024L));
        builder.setMemoryCache(new LruResourceCache(16L * 1024L * 1024L));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
