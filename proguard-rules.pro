-dontwarn com.google.**
-dontwarn com.zafaco.**

-keeppackagenames org.jsoup.nodes

-keepattributes SourceFile,LineNumberTable
-keep class com.zafaco.**
-keepclassmembers class com.zafaco.** { *; }
-keep enum com.zafaco.**
-keepclassmembers enum com.zafaco.** { *; }
-keep interface com.zafaco.**
-keepclassmembers interface com.zafaco.** { *; }

-keepattributes Signature