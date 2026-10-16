import Foundation

final class CommaDataSource: IDataSource {

    private var continuation: AsyncStream<CarState>.Continuation?
    let incomingMessages: AsyncStream<CarState>

    private var context: UnsafeMutableRawPointer?
    private var group: UnsafeMutableRawPointer?
    private var decoder: UnsafeMutableRawPointer?
    // Serializes start/stop of the native receive loop, and owns the callback
    // context it borrows until stop has joined the worker thread.
    private let loopLock = NSLock()
    private var loopContext: Unmanaged<CallbackBox>?

    private let vehicleType: String
    private let dbcFiles: [(busIndex: Int, resourceName: String)]

    init(vehicleType: String = "tesla_party",
         dbcFiles: [(busIndex: Int, resourceName: String)] = [(2, "bus_2_tesla_party")]) {
        self.vehicleType = vehicleType
        self.dbcFiles = dbcFiles

        var capturedContinuation: AsyncStream<CarState>.Continuation?
        incomingMessages = AsyncStream(bufferingPolicy: .bufferingNewest(16)) { continuation in
            capturedContinuation = continuation
        }
        self.continuation = capturedContinuation
    }

    func connect(address: String) {
        // Free anything left from a previous connect() so reconnects don't leak.
        releaseNativeResources()

        // Load DBC files from bundle and create decoder
        var dbcContents: [String] = []
        var busIndices: [CInt] = []
        for (busIndex, resourceName) in dbcFiles {
            let url = Bundle.main.url(forResource: resourceName, withExtension: "dbc")
            guard let url, let content = try? String(contentsOf: url) else {
                print("[CommaDataSource] failed to load \(resourceName).dbc — bundle path: \(Bundle.main.bundlePath)")
                continue
            }
            print("[CommaDataSource] loaded \(resourceName).dbc (\(content.count) bytes) from \(url.path)")
            dbcContents.append(content)
            busIndices.append(CInt(busIndex))
        }

        let dec = dbcContents.withCStringArray { dbcPtrs in
            busIndices.withUnsafeBufferPointer { busBuf in
                bridge_create_vehicle_decoder(dbcPtrs, busBuf.baseAddress, Int32(dbcContents.count), vehicleType)
            }
        }
        self.decoder = dec

        let ctx = bridge_create_context()
        self.context = ctx

        let endpoints: [String] = ["can", "selfdriveState", "selfdriveStateSP"]
        let grp = endpoints.withCStringArray { ptrs in
            bridge_create_sub_sockets(ctx, ptrs, Int32(endpoints.count), address)
        }
        self.group = grp

        // Pass continuation as unmanaged context to the C callback. Released in
        // releaseNativeResources() after the native worker has been joined.
        let cont = self.continuation
        let box = Unmanaged.passRetained(CallbackBox(cont))

        // The C call returns immediately (the loop runs on a native worker),
        // so holding the lock here is cheap and keeps start/stop ordered.
        loopLock.lock()
        loopContext = box
        bridge_start_receive_loop(grp, dec, box.toOpaque()) { ctx, statePtr in
            guard let ctx, let statePtr else { return }
            let callbackBox = Unmanaged<CallbackBox>.fromOpaque(ctx).takeUnretainedValue()
            let carState = CarState(from: statePtr.pointee)
            callbackBox.continuation?.yield(carState)
        }
        loopLock.unlock()
    }

    func disconnect() {
        releaseNativeResources()
        continuation?.finish()
    }

    private func releaseNativeResources() {
        loopLock.lock()
        // Blocks until the native worker has exited, so no callback can run
        // after this and it is safe to release the context below.
        bridge_stop_receive_loop()
        loopContext?.release()
        loopContext = nil
        loopLock.unlock()

        if let group { bridge_delete_sub_sockets(group) }
        if let decoder { bridge_destroy_vehicle_decoder(decoder) }
        if let context { bridge_delete_context(context) }
        self.group = nil
        self.decoder = nil
        self.context = nil
    }
}

private final class CallbackBox {
    let continuation: AsyncStream<CarState>.Continuation?
    init(_ continuation: AsyncStream<CarState>.Continuation?) {
        self.continuation = continuation
    }
}

private extension Array where Element == String {
    func withCStringArray<R>(_ body: (UnsafeMutablePointer<UnsafePointer<CChar>?>) -> R) -> R {
        var cStrings = self.map { strdup($0) }
        defer { cStrings.forEach { free($0) } }
        return cStrings.withUnsafeMutableBufferPointer { buffer in
            let bound = buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) { $0 }
            return body(bound)
        }
    }
}
