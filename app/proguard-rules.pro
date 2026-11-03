# The libraries (Leanback, Glide, media3) bring their own rules. The app's own classes are kept as they are:
# names are used in saved settings and backups, and the app is small, so shrinking the libraries is what pays.
-keep class ru.lazytv.** { *; }
-dontwarn org.jspecify.annotations.**
