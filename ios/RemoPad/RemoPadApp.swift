import SwiftUI

@main
struct RemoPadApp: App {
    @StateObject private var transport = UDPTransport()

    var body: some Scene {
        WindowGroup {
            ContentView(transport: transport)
        }
    }
}
