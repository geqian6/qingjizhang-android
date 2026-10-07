# 轻记账 ProGuard 规则
# 当前 release 未开启混淆（isMinifyEnabled = false），此文件留作后续使用。

# Room 生成的实现类不能被裁掉
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
