package com.enpo.connect.app.ui

import com.enpo.connect.app.state.EnpoBootstrapState

data class EnpoStrings(
    val productName: String,
    val organizationName: String,
    val foundationEyebrow: String,
    val foundationTitle: String,
    val foundationBody: String,
    val sliceNotice: String,
    val settings: String,
    val language: String,
    val appearance: String,
    val about: String,
    val arabic: String,
    val english: String,
    val system: String,
    val light: String,
    val dark: String,
    val back: String,
    val version: String,
    val platformFoundation: String,
    val platformFoundationBody: String,
    val deviceState: String,
    val unpaired: String,
    val deviceCredentialAvailable: String,
    val credentialUnreadable: String,
    val initializationError: String,
    val deferredCapabilities: String,
    val deferredCapabilitiesBody: String,
) {
    fun allValues(): List<String> = listOf(
        productName,
        organizationName,
        foundationEyebrow,
        foundationTitle,
        foundationBody,
        sliceNotice,
        settings,
        language,
        appearance,
        about,
        arabic,
        english,
        system,
        light,
        dark,
        back,
        version,
        platformFoundation,
        platformFoundationBody,
        deviceState,
        unpaired,
        deviceCredentialAvailable,
        credentialUnreadable,
        initializationError,
        deferredCapabilities,
        deferredCapabilitiesBody,
    )

    fun deviceStateText(state: EnpoBootstrapState): String = when (state) {
        EnpoBootstrapState.Initializing -> platformFoundationBody
        EnpoBootstrapState.Unpaired -> unpaired
        EnpoBootstrapState.DeviceCredentialAvailable -> deviceCredentialAvailable
        EnpoBootstrapState.CredentialUnreadable -> credentialUnreadable
        EnpoBootstrapState.Error -> initializationError
    }
}

fun enpoStrings(languageTag: String): EnpoStrings =
    if (languageTag.startsWith("ar")) ArabicStrings else EnglishStrings

private val ArabicStrings = EnpoStrings(
    productName = "ENPO Connect",
    organizationName = "البريد المصري",
    foundationEyebrow = "CONNECT",
    foundationTitle = "رفيقك الآمن لمنصة Connect",
    foundationBody = "هيكل ENPO Connect يعمل الآن داخل منصة Bot Global مع الحفاظ على هوية التطبيق.",
    sliceNotice = "تجهز هذه المرحلة هوية التثبيت وحالة الجهاز فقط؛ الربط والإشعارات ستنتقل في مراحل مستقلة.",
    settings = "الإعدادات",
    language = "اللغة",
    appearance = "المظهر",
    about = "حول التطبيق",
    arabic = "العربية",
    english = "English",
    system = "النظام",
    light = "فاتح",
    dark = "داكن",
    back = "رجوع",
    version = "رقم الإصدار",
    platformFoundation = "أساس المنصة جاهز",
    platformFoundationBody = "اللغة والمظهر والتنقل والتخزين التفضيلي تستخدم قدرات المنصة المشتركة.",
    deviceState = "حالة الجهاز",
    unpaired = "لا توجد بيانات ربط محفوظة. لم يتم إجراء أي اتصال بالشبكة.",
    deviceCredentialAvailable = "بيانات الجهاز المشفرة متاحة للاستعادة في مرحلة الربط القادمة.",
    credentialUnreadable = "توجد بيانات جهاز لا يمكن قراءتها بأمان؛ لم يتم حذفها أو استبدالها.",
    initializationError = "تعذر فحص حالة الجهاز محلياً.",
    deferredCapabilities = "القدرات المؤجلة",
    deferredCapabilitiesBody = "لا يوجد اقتران أو Firebase أو إشعارات أو اتصال بالخلفية في هذه المرحلة.",
)

private val EnglishStrings = EnpoStrings(
    productName = "ENPO Connect",
    organizationName = "Egypt Post",
    foundationEyebrow = "CONNECT",
    foundationTitle = "Your secure Connect companion",
    foundationBody = "The ENPO Connect shell now runs inside Bot Global Platform while preserving its app identity.",
    sliceNotice = "This slice prepares installation identity and local device state only; pairing and notifications move in dedicated phases.",
    settings = "Settings",
    language = "Language",
    appearance = "Appearance",
    about = "About",
    arabic = "العربية",
    english = "English",
    system = "System",
    light = "Light",
    dark = "Dark",
    back = "Back",
    version = "Version",
    platformFoundation = "Platform foundation ready",
    platformFoundationBody = "Language, appearance, navigation, and preference storage use shared platform capabilities.",
    deviceState = "Device state",
    unpaired = "No stored pairing data is available. No network request was made.",
    deviceCredentialAvailable = "Encrypted device data is available for restoration in the pairing slice.",
    credentialUnreadable = "Device data exists but cannot be read safely; it was not removed or replaced.",
    initializationError = "The local device state could not be inspected.",
    deferredCapabilities = "Capabilities intentionally deferred",
    deferredCapabilitiesBody = "Pairing, Firebase, notifications, and backend access are not included in this slice.",
)
