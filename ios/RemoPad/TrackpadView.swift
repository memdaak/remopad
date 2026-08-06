import SwiftUI
import UIKit

struct TrackpadView: UIViewRepresentable {
    @ObservedObject var transport: UDPTransport
    let pointerSensitivity: Double
    let scrollSensitivity: Double

    func makeUIView(context: Context) -> TrackpadUIView {
        let view = TrackpadUIView()
        view.onMove = { [weak transport] dx, dy in
            transport?.sendMove(dx: dx, dy: dy)
        }
        view.onScroll = { [weak transport] dx, dy in
            transport?.sendScroll(dx: dx, dy: dy)
        }
        view.onButton = { [weak transport] button, isDown in
            transport?.sendButton(button, isDown: isDown)
        }
        view.onClick = { [weak transport] button in
            transport?.sendClick(button)
        }
        return view
    }

    func updateUIView(_ uiView: TrackpadUIView, context: Context) {
        uiView.pointerSensitivity = CGFloat(pointerSensitivity)
        uiView.scrollSensitivity = CGFloat(scrollSensitivity)
    }
}

final class TrackpadUIView: UIView {
    var onMove: ((Float, Float) -> Void)?
    var onScroll: ((Float, Float) -> Void)?
    var onButton: ((UInt8, Bool) -> Void)?
    var onClick: ((UInt8) -> Void)?

    var pointerSensitivity: CGFloat = 1
    var scrollSensitivity: CGFloat = 1

    private struct TouchState {
        let start: CGPoint
        var last: CGPoint
    }

    private var activeTouches: [ObjectIdentifier: TouchState] = [:]
    private var sessionStartedAt: TimeInterval = 0
    private var sessionMovement: CGFloat = 0
    private var maximumTouchCount = 0
    private var isDragging = false
    private var longPressWorkItem: DispatchWorkItem?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isExclusiveTouch = true
        backgroundColor = UIColor.secondarySystemGroupedBackground
        layer.borderColor = UIColor.separator.cgColor
        layer.borderWidth = 0.5
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isMultipleTouchEnabled = true
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if activeTouches.isEmpty {
            sessionStartedAt = ProcessInfo.processInfo.systemUptime
            sessionMovement = 0
            maximumTouchCount = 0
            isDragging = false
        }

        for touch in touches {
            let point = touch.location(in: self)
            activeTouches[ObjectIdentifier(touch)] = TouchState(start: point, last: point)
        }

        maximumTouchCount = max(maximumTouchCount, activeTouches.count)
        if activeTouches.count == 1 {
            scheduleLongPress()
        } else {
            cancelLongPress()
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        var deltaX: CGFloat = 0
        var deltaY: CGFloat = 0
        var updatedCount: CGFloat = 0

        for touch in touches {
            let identifier = ObjectIdentifier(touch)
            guard var state = activeTouches[identifier] else { continue }

            let point = touch.location(in: self)
            deltaX += point.x - state.last.x
            deltaY += point.y - state.last.y
            sessionMovement = max(sessionMovement, hypot(point.x - state.start.x,
                                                          point.y - state.start.y))
            state.last = point
            activeTouches[identifier] = state
            updatedCount += 1
        }

        guard updatedCount > 0 else { return }
        deltaX /= updatedCount
        deltaY /= updatedCount

        if sessionMovement > 6 { cancelLongPress() }

        if activeTouches.count == 1 {
            onMove?(Float(deltaX * pointerSensitivity),
                    Float(deltaY * pointerSensitivity))
        } else if activeTouches.count == 2 {
            onScroll?(Float(deltaX * scrollSensitivity),
                      Float(deltaY * scrollSensitivity))
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        removeTouches(touches)
        if activeTouches.isEmpty { finishSession(cancelled: false) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        removeTouches(touches)
        if activeTouches.isEmpty { finishSession(cancelled: true) }
    }

    private func removeTouches(_ touches: Set<UITouch>) {
        for touch in touches {
            activeTouches.removeValue(forKey: ObjectIdentifier(touch))
        }
    }

    private func scheduleLongPress() {
        cancelLongPress()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self,
                  self.activeTouches.count == 1,
                  self.sessionMovement < 6,
                  !self.isDragging else { return }

            self.isDragging = true
            self.onButton?(1, true)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        longPressWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.32, execute: workItem)
    }

    private func cancelLongPress() {
        longPressWorkItem?.cancel()
        longPressWorkItem = nil
    }

    private func finishSession(cancelled: Bool) {
        cancelLongPress()

        if isDragging {
            onButton?(1, false)
        } else if !cancelled {
            let duration = ProcessInfo.processInfo.systemUptime - sessionStartedAt
            if duration <= 0.32 && sessionMovement < 12 {
                if maximumTouchCount == 1 {
                    onClick?(1)
                    UISelectionFeedbackGenerator().selectionChanged()
                } else if maximumTouchCount == 2 {
                    onClick?(2)
                    UISelectionFeedbackGenerator().selectionChanged()
                }
            }
        }

        isDragging = false
        sessionMovement = 0
        maximumTouchCount = 0
    }
}
