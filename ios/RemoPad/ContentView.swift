import SwiftUI

struct ContentView: View {
    @ObservedObject var transport: UDPTransport

    @AppStorage("receiverHost") private var host = "192.168.1.100"
    @AppStorage("receiverPort") private var port = "51234"
    @AppStorage("pairingCode") private var pairingCode = "246810"
    @AppStorage("pointerSensitivity") private var pointerSensitivity = 1.0
    @AppStorage("scrollSensitivity") private var scrollSensitivity = 1.0

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                connectionPanel

                TrackpadView(
                    transport: transport,
                    pointerSensitivity: pointerSensitivity,
                    scrollSensitivity: scrollSensitivity
                )
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("触控区域")
                            .font(.headline)
                        Text("一指移动和轻点 · 长按拖动 · 双指滚动和右键")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(18)
                    .allowsHitTesting(false)
                }
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding([.horizontal, .bottom], 12)
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("RemoPad")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }

    private var connectionPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                TextField("电脑 IPv4", text: $host)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled(true)

                TextField("端口", text: $port)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .frame(width: 86)

                TextField("配对码", text: $pairingCode)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .frame(width: 100)

                Button(transport.isConnected ? "断开" : "连接") {
                    if transport.isConnected || transport.isConnecting {
                        transport.disconnect()
                    } else {
                        connect()
                    }
                }
                .buttonStyle(.borderedProminent)
            }

            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 9, height: 9)
                Text(transport.statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)

                Spacer()

                Text("指针")
                    .font(.caption)
                Slider(value: $pointerSensitivity, in: 0.4...2.5)
                    .frame(maxWidth: 150)

                Text("滚动")
                    .font(.caption)
                Slider(value: $scrollSensitivity, in: 0.4...2.5)
                    .frame(maxWidth: 150)
            }
        }
        .padding(12)
    }

    private var statusColor: Color {
        if transport.isConnected { return .green }
        if transport.isConnecting { return .orange }
        return .gray
    }

    private func connect() {
        guard let parsedPort = UInt16(port),
              let parsedCode = UInt32(pairingCode),
              parsedCode <= 999_999 else {
            transport.showConfigurationError()
            return
        }

        transport.connect(host: host.trimmingCharacters(in: .whitespacesAndNewlines),
                          port: parsedPort,
                          pairingCode: parsedCode)
    }
}
