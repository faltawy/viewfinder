package app.viewfinder.ptp

/** Standard PTP (ISO 15740) operation codes. Vendor codes live with their driver. */
object Op {
    const val GetDeviceInfo = 0x1001
    const val OpenSession = 0x1002
    const val CloseSession = 0x1003
    const val GetStorageIDs = 0x1004
    const val GetStorageInfo = 0x1005
    const val GetNumObjects = 0x1006
    const val GetObjectHandles = 0x1007
    const val GetObjectInfo = 0x1008
    const val GetObject = 0x1009
    const val GetThumb = 0x100A
    const val DeleteObject = 0x100B
    const val InitiateCapture = 0x100E
    const val FormatStore = 0x100F
    const val GetDevicePropDesc = 0x1014
    const val GetDevicePropValue = 0x1015
    const val SetDevicePropValue = 0x1016
    const val GetPartialObject = 0x101B

    private val names = mapOf(
        GetDeviceInfo to "GetDeviceInfo", OpenSession to "OpenSession", CloseSession to "CloseSession",
        GetStorageIDs to "GetStorageIDs", GetStorageInfo to "GetStorageInfo", GetNumObjects to "GetNumObjects",
        GetObjectHandles to "GetObjectHandles", GetObjectInfo to "GetObjectInfo", GetObject to "GetObject",
        GetThumb to "GetThumb", DeleteObject to "DeleteObject", InitiateCapture to "InitiateCapture", FormatStore to "FormatStore",
        GetDevicePropDesc to "GetDevicePropDesc", GetDevicePropValue to "GetDevicePropValue",
        SetDevicePropValue to "SetDevicePropValue", GetPartialObject to "GetPartialObject",
    )
    private val vendorNames = mutableMapOf<Int, String>()

    /** Drivers register their vendor operation names so logs stay readable. */
    fun registerNames(extra: Map<Int, String>) = synchronized(vendorNames) { vendorNames.putAll(extra) }

    fun name(code: Int): String =
        names[code] ?: synchronized(vendorNames) { vendorNames[code] } ?: hex16(code)
}

/** PTP response codes. */
object Rc {
    const val OK = 0x2001
    const val GeneralError = 0x2002
    const val SessionNotOpen = 0x2003
    const val InvalidTransactionID = 0x2004
    const val OperationNotSupported = 0x2005
    const val ParameterNotSupported = 0x2006
    const val IncompleteTransfer = 0x2007
    const val InvalidStorageID = 0x2008
    const val InvalidObjectHandle = 0x2009
    const val DevicePropNotSupported = 0x200A
    const val StoreFull = 0x200C
    const val StoreNotAvailable = 0x2013
    const val NoThumbnailPresent = 0x2010
    const val DeviceBusy = 0x2019
    const val InvalidParameter = 0x201D
    const val SessionAlreadyOpen = 0x201E
    const val TransactionCancelled = 0x201F

    private val names = mapOf(
        OK to "OK", GeneralError to "GeneralError", SessionNotOpen to "SessionNotOpen",
        InvalidTransactionID to "InvalidTransactionID", OperationNotSupported to "OperationNotSupported",
        ParameterNotSupported to "ParameterNotSupported", IncompleteTransfer to "IncompleteTransfer",
        InvalidStorageID to "InvalidStorageID", InvalidObjectHandle to "InvalidObjectHandle",
        DevicePropNotSupported to "DevicePropNotSupported", StoreFull to "StoreFull",
        StoreNotAvailable to "StoreNotAvailable", NoThumbnailPresent to "NoThumbnailPresent",
        DeviceBusy to "DeviceBusy", InvalidParameter to "InvalidParameter",
        SessionAlreadyOpen to "SessionAlreadyOpen", TransactionCancelled to "TransactionCancelled",
    )

    private val vendorNames = mutableMapOf<Int, String>()

    fun registerNames(extra: Map<Int, String>) = synchronized(vendorNames) { vendorNames.putAll(extra) }

    fun name(code: Int): String = names[code] ?: synchronized(vendorNames) { vendorNames[code] } ?: hex16(code)
}

/** PTP event codes. */
object Ev {
    const val ObjectAdded = 0x4002
    const val ObjectRemoved = 0x4003
    const val StoreAdded = 0x4004
    const val StoreRemoved = 0x4005
    const val DevicePropChanged = 0x4006
    const val StorageInfoChanged = 0x400C
    const val CaptureComplete = 0x400D
}

/** PTP device property codes. */
object Prop {
    const val BatteryLevel = 0x5001
    const val DateTime = 0x5011
}

/** PTP object format codes. Many cameras report RAW files as [Undefined], so file kinds come from the name. */
object Format {
    const val Undefined = 0x3000
    const val Association = 0x3001
    const val ExifJpeg = 0x3801
}
