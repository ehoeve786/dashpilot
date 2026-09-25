import SwiftUI
import WebKit

// Serves local bundle files under app://localhost/ so that fetch(), ES modules,
// and WebAssembly.instantiateStreaming all work (fetch() blocks file:// URLs in WKWebView).
final class AppSchemeHandler: NSObject, WKURLSchemeHandler {

    private let bundleDir: URL

    init(bundleDir: URL) {
        self.bundleDir = bundleDir
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let requestURL = urlSchemeTask.request.url,
              var path = requestURL.path.removingPercentEncoding else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }

        if path.hasPrefix("/") { path = String(path.dropFirst()) }
        let fileURL = bundleDir.appendingPathComponent(path)

        guard !path.hasSuffix(".meta") else {
            let response = HTTPURLResponse(url: requestURL, statusCode: 404,
                                           httpVersion: "HTTP/1.1", headerFields: nil)!
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(Data())
            urlSchemeTask.didFinish()
            return
        }

        do {
            let data = try Data(contentsOf: fileURL)
            let mimeType = Self.mimeType(for: fileURL.pathExtension)
            print("[AppSchemeHandler] \(mimeType) \(path) (\(data.count) bytes)")
            let response = HTTPURLResponse(
                url: requestURL,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: [
                    "Content-Type": mimeType,
                    "Content-Length": "\(data.count)",
                    "Access-Control-Allow-Origin": "*",
                ]
            )!
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch {
            print("[AppSchemeHandler] MISSING: \(path) in \(bundleDir.path)")
            let response = HTTPURLResponse(url: requestURL, statusCode: 404,
                                           httpVersion: "HTTP/1.1", headerFields: nil)!
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(Data())
            urlSchemeTask.didFinish()
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private static func mimeType(for ext: String) -> String {
        switch ext.lowercased() {
        case "html":        return "text/html; charset=utf-8"
        case "js", "mjs":  return "application/javascript"
        case "wasm":        return "application/wasm"
        case "css":         return "text/css"
        case "json":        return "application/json"
        case "png":         return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "svg":         return "image/svg+xml"
        case "wgsl":        return "text/plain"
        case "glb":         return "model/gltf-binary"
        default:            return "application/octet-stream"
        }
    }
}

// web-compose stores its layout document through the host: a WKWebView served
// over a custom scheme has an opaque origin, where localStorage is unavailable.
enum ComposeLayoutStore {
    static let key = "dash_compose_layout"
    static let dashboardId = "compose"
    static let messageName = "composeLayout"
    static let editingMessageName = "composeEditing"

    static var saved: String? { UserDefaults.standard.string(forKey: key) }

    static func save(_ json: String) {
        UserDefaults.standard.set(json, forKey: key)
    }

    // The layout is read before the first script runs, so the page renders it directly.
    static func injectionScript() -> WKUserScript? {
        guard let json = saved,
              let data = try? JSONSerialization.data(withJSONObject: json, options: [.fragmentsAllowed]),
              let literal = String(data: data, encoding: .utf8) else { return nil }
        return WKUserScript(source: "window.__DASHPILOT_COMPOSE_LAYOUT__ = \(literal);",
                            injectionTime: .atDocumentStart,
                            forMainFrameOnly: true)
    }
}

// True while the web-compose editor is open. The dashboard carousel reads a
// horizontal drag as "next dashboard", which would fire while a widget is being
// dragged or resized, so the carousel suspends itself for the duration.
@Observable
final class ComposeEditingState {
    static let shared = ComposeEditingState()
    var isEditing = false
}

struct WebDashView: UIViewRepresentable {

    let url: String
    let incomingMessages: AsyncStream<DashState>

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    private static let localApps: Set<String> = ["vanilla", "retro", "ambient", "analog", "compose"]

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(context.coordinator, name: "log")

        if url == ComposeLayoutStore.dashboardId {
            config.userContentController.add(context.coordinator, name: ComposeLayoutStore.messageName)
            config.userContentController.add(context.coordinator, name: ComposeLayoutStore.editingMessageName)
            if let script = ComposeLayoutStore.injectionScript() {
                config.userContentController.addUserScript(script)
            }
        }
        // A dashboard that cannot edit never leaves the carousel suspended.
        ComposeEditingState.shared.isEditing = false

        if Self.localApps.contains(url) {
            let bundleDir = Bundle.main.bundleURL.appendingPathComponent("web-\(url)")
            config.setURLSchemeHandler(AppSchemeHandler(bundleDir: bundleDir), forURLScheme: "app")
        }

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.scrollView.isScrollEnabled = false
        webView.navigationDelegate = context.coordinator
        webView.backgroundColor = .black
        webView.isOpaque = false
        context.coordinator.webView = webView
        context.coordinator.incomingMessages = incomingMessages

        if url.hasPrefix("http") || url.hasPrefix("https") {
            webView.load(URLRequest(url: URL(string: url)!))
        } else if Self.localApps.contains(url) {
            webView.load(URLRequest(url: URL(string: "app://localhost/index.html")!))
        }

        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {

        weak var webView: WKWebView?
        var incomingMessages: AsyncStream<DashState>?
        private var receiveTask: Task<Void, Never>?

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let stream = incomingMessages else { return }
            startReceiving(stream)
        }

        private func startReceiving(_ stream: AsyncStream<DashState>) {
            receiveTask?.cancel()
            receiveTask = Task { @MainActor in
                for await state in stream {
                    let json = state.toJSONString()
                    webView?.evaluateJavaScript(
                        "window.receiveMessage && window.receiveMessage(\(json))",
                        completionHandler: nil
                    )
                }
            }
        }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            if message.name == "log" {
                print("WebView JS: \(message.body)")
            } else if message.name == ComposeLayoutStore.messageName, let json = message.body as? String {
                ComposeLayoutStore.save(json)
            } else if message.name == ComposeLayoutStore.editingMessageName {
                ComposeEditingState.shared.isEditing = (message.body as? NSNumber)?.boolValue ?? false
            }
        }

        deinit {
            receiveTask?.cancel()
        }
    }
}
