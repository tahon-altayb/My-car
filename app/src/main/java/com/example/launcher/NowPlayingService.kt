package com.example.launcher

import android.service.notification.NotificationListenerService

/**
 * خدمة فارغة، وجودها مطلوب عشان النظام يسمح للانشر يقرأ جلسات الموسيقى.
 * المستخدم يفعّلها من: الإعدادات > الإشعارات > الوصول للإشعارات.
 */
class NowPlayingService : NotificationListenerService()
