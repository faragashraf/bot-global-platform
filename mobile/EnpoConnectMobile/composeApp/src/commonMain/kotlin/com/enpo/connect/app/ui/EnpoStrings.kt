package com.enpo.connect.app.ui

import com.enpo.connect.app.state.EnpoBootstrapState
import com.enpo.connect.app.pairing.EnpoPairingError
import com.enpo.connect.app.pairing.EnpoPairingState

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
    val pairingTitle: String,
    val pairingBody: String,
    val scanQr: String,
    val scannerPrompt: String,
    val scanning: String,
    val validating: String,
    val claiming: String,
    val persistingCredential: String,
    val retry: String,
    val pairingSuccessTitle: String,
    val pairingSuccessBody: String,
    val continueToApp: String,
    val invalidQr: String,
    val unsupportedQr: String,
    val challengeUnavailable: String,
    val pairingUnauthorized: String,
    val networkUnavailable: String,
    val pairingTimeout: String,
    val serverUnavailable: String,
    val persistenceFailure: String,
    val cameraPermissionDenied: String,
    val cameraUnavailable: String,
    val scannerUnavailable: String,
    val pairingUnknown: String,
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
        pairingTitle,
        pairingBody,
        scanQr,
        scannerPrompt,
        scanning,
        validating,
        claiming,
        persistingCredential,
        retry,
        pairingSuccessTitle,
        pairingSuccessBody,
        continueToApp,
        invalidQr,
        unsupportedQr,
        challengeUnavailable,
        pairingUnauthorized,
        networkUnavailable,
        pairingTimeout,
        serverUnavailable,
        persistenceFailure,
        cameraPermissionDenied,
        cameraUnavailable,
        scannerUnavailable,
        pairingUnknown,
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

    fun pairingStateText(state: EnpoPairingState): String = when (state) {
        EnpoPairingState.Unpaired -> unpaired
        EnpoPairingState.Scanning -> scanning
        EnpoPairingState.Validating -> validating
        EnpoPairingState.Claiming -> claiming
        EnpoPairingState.PersistingCredential -> persistingCredential
        EnpoPairingState.Paired -> pairingSuccessBody
        is EnpoPairingState.RecoverableError -> pairingErrorText(state.error)
        is EnpoPairingState.FatalError -> pairingErrorText(state.error)
    }

    private fun pairingErrorText(error: EnpoPairingError): String = when (error) {
        EnpoPairingError.InvalidQr -> invalidQr
        EnpoPairingError.UnsupportedQr -> unsupportedQr
        EnpoPairingError.Expired,
        EnpoPairingError.AlreadyUsed,
        EnpoPairingError.InvalidExpiredOrAlreadyUsed,
        -> challengeUnavailable
        EnpoPairingError.Unauthorized,
        EnpoPairingError.Forbidden,
        -> pairingUnauthorized
        EnpoPairingError.NetworkUnavailable -> networkUnavailable
        EnpoPairingError.Timeout -> pairingTimeout
        EnpoPairingError.ServerUnavailable,
        EnpoPairingError.ServerError,
        -> serverUnavailable
        EnpoPairingError.PersistenceFailure -> persistenceFailure
        EnpoPairingError.CredentialUnreadable -> credentialUnreadable
        EnpoPairingError.CameraPermissionDenied -> cameraPermissionDenied
        EnpoPairingError.CameraUnavailable -> cameraUnavailable
        EnpoPairingError.ScannerUnavailable -> scannerUnavailable
        EnpoPairingError.Unknown -> pairingUnknown
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
    sliceNotice = "اكتمل ربط الجهاز بأمان. ستنتقل Firebase والإشعارات في مرحلة مستقلة.",
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
    unpaired = "هذا الجهاز غير مرتبط بعد.",
    deviceCredentialAvailable = "بيانات الجهاز المشفرة محفوظة ومتاحة.",
    credentialUnreadable = "توجد بيانات جهاز لا يمكن قراءتها بأمان؛ لم يتم حذفها أو استبدالها.",
    initializationError = "تعذر فحص حالة الجهاز محلياً.",
    pairingTitle = "اربط هذا الجهاز بمنصة Connect",
    pairingBody = "افتح Connect V2 واعرض رمز الربط المؤقت، ثم اسمح بالكاميرا وامسح الرمز. لا يحتوي الرمز على بياناتك الشخصية.",
    scanQr = "مسح رمز QR",
    scannerPrompt = "ضع رمز ربط Connect داخل الإطار",
    scanning = "الكاميرا مفتوحة لقراءة رمز الربط فقط.",
    validating = "جارٍ التحقق من صيغة الرمز…",
    claiming = "جارٍ تأكيد الربط مع Bot Global…",
    persistingCredential = "جارٍ حفظ بيانات الجهاز بصورة مشفرة…",
    retry = "المحاولة مرة أخرى",
    pairingSuccessTitle = "تم ربط الجهاز بنجاح",
    pairingSuccessBody = "تم حفظ بيانات الجهاز المشفرة قبل إكمال الربط.",
    continueToApp = "الدخول إلى ENPO Connect",
    invalidQr = "رمز QR غير صالح. اعرض رمز ربط جديد من Connect V2.",
    unsupportedQr = "هذا الرمز ليس رمز ربط Connect V2.",
    challengeUnavailable = "انتهت صلاحية رمز الربط أو سبق استخدامه أو لم يعد صالحاً. أنشئ رمزاً جديداً.",
    pairingUnauthorized = "خدمة الربط رفضت الطلب. حاول لاحقاً أو تواصل مع الدعم.",
    networkUnavailable = "تعذر الوصول إلى الشبكة. تحقق من الاتصال ثم حاول مرة أخرى.",
    pairingTimeout = "انتهت مهلة الاتصال. تحقق من الشبكة ثم حاول مرة أخرى.",
    serverUnavailable = "خدمة الربط غير متاحة مؤقتاً. حاول مرة أخرى لاحقاً.",
    persistenceFailure = "اكتمل رد الخدمة لكن تعذر تأمين بيانات الجهاز. لا تحذف التطبيق وتواصل مع الدعم.",
    cameraPermissionDenied = "يلزم السماح بالكاميرا لمسح الرمز. فعّل الإذن من إعدادات الجهاز إذا كان محظوراً.",
    cameraUnavailable = "الكاميرا غير متاحة على هذا الجهاز.",
    scannerUnavailable = "تعذر فتح ماسح QR. أغلق التطبيقات التي تستخدم الكاميرا ثم حاول مرة أخرى.",
    pairingUnknown = "تعذر إكمال الربط بأمان. حاول مرة أخرى.",
    deferredCapabilities = "القدرات المؤجلة",
    deferredCapabilitiesBody = "لا يوجد Firebase أو FCM أو إشعارات في هذه المرحلة.",
)

private val EnglishStrings = EnpoStrings(
    productName = "ENPO Connect",
    organizationName = "Egypt Post",
    foundationEyebrow = "CONNECT",
    foundationTitle = "Your secure Connect companion",
    foundationBody = "The ENPO Connect shell now runs inside Bot Global Platform while preserving its app identity.",
    sliceNotice = "Device pairing is securely available. Firebase and notifications remain a separate migration slice.",
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
    unpaired = "This device is not paired yet.",
    deviceCredentialAvailable = "Encrypted device data is stored and available.",
    credentialUnreadable = "Device data exists but cannot be read safely; it was not removed or replaced.",
    initializationError = "The local device state could not be inspected.",
    pairingTitle = "Pair this device with Connect",
    pairingBody = "Open Connect V2 and display a fresh temporary pairing code, then allow camera access and scan it. The code contains no personal information.",
    scanQr = "Scan QR code",
    scannerPrompt = "Place the Connect pairing code inside the frame",
    scanning = "The camera is open only to read the pairing code.",
    validating = "Validating the code format…",
    claiming = "Confirming pairing with Bot Global…",
    persistingCredential = "Saving encrypted device data…",
    retry = "Try again",
    pairingSuccessTitle = "Device paired successfully",
    pairingSuccessBody = "Encrypted device data was stored before pairing completed.",
    continueToApp = "Enter ENPO Connect",
    invalidQr = "This QR code is invalid. Display a fresh pairing code from Connect V2.",
    unsupportedQr = "This is not a Connect V2 pairing code.",
    challengeUnavailable = "This pairing code expired, was already used, or is no longer valid. Create a fresh code.",
    pairingUnauthorized = "The pairing service rejected the request. Try later or contact support.",
    networkUnavailable = "The network is unavailable. Check the connection and try again.",
    pairingTimeout = "The connection timed out. Check the network and try again.",
    serverUnavailable = "The pairing service is temporarily unavailable. Try again later.",
    persistenceFailure = "The service replied, but device data could not be secured. Keep the app installed and contact support.",
    cameraPermissionDenied = "Camera permission is required. Enable it in device settings if it is blocked.",
    cameraUnavailable = "The camera is unavailable on this device.",
    scannerUnavailable = "The QR scanner could not open. Close other camera apps and try again.",
    pairingUnknown = "Pairing could not be completed safely. Try again.",
    deferredCapabilities = "Capabilities intentionally deferred",
    deferredCapabilitiesBody = "Firebase, FCM, and notifications are not included in this slice.",
)
