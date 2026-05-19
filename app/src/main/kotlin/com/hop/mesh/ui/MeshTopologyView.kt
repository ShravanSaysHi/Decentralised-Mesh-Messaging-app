package com.hop.mesh.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.hop.mesh.routing.RoutingEntry
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom View that renders a visual mesh topology.
 * Local node at center, peers arranged radially.
 * Color codes: green=connected, amber=reachable (multi-hop), gray=stale.
 */
class MeshTopologyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class MeshNode(
        val nodeId: String,
        val deviceName: String,
        val hopCount: Int,
        val nextHop: String, // The UUID of the peer we route through
        val isStale: Boolean = false
    )

    private var localNodeId: String = "You"
    private var nodes: List<MeshNode> = emptyList()
    private val nodePositions = mutableMapOf<String, PointF>()
    
    /** Callback when a node is clicked. Passes the nodeId (UUID). */
    var onNodeClick: ((String) -> Unit)? = null

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#495057")
        textSize = 24f
    }

    private val hopLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#6C757D")
        textSize = 20f
    }

    private val centerNodeColor = Color.parseColor("#0D6EFD")
    private val connectedColor = Color.parseColor("#28A745")
    private val reachableColor = Color.parseColor("#FFC107")
    private val staleColor = Color.parseColor("#ADB5BD")
    private val lineLightColor = Color.parseColor("#DEE2E6")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8F9FA")
        style = Paint.Style.FILL
    }

    fun setTopology(localId: String, meshNodes: List<MeshNode>) {
        localNodeId = if (localId.length > 8) localId.take(8) + "…" else localId
        nodes = meshNodes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(cx, cy) * 0.65f
        val centerRadius = 32f
        val nodeRadius = 22f

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, bgPaint)

        if (nodes.isEmpty()) {
            // Empty state
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                color = Color.parseColor("#ADB5BD")
                textSize = 28f
            }
            canvas.drawText("No nodes discovered yet", cx, cy - 20f, emptyPaint)
            canvas.drawText("Start hosting or discovering", cx, cy + 20f, emptyPaint)

            // Draw center node anyway
            nodePaint.color = centerNodeColor
            canvas.drawCircle(cx, cy + 60f, centerRadius, nodePaint)
            canvas.drawCircle(cx, cy + 60f, centerRadius, nodeStrokePaint)
            textPaint.textSize = 22f
            canvas.drawText("You", cx, cy + 60f + 8f, textPaint)
            return
        }

        val angleStep = (2 * Math.PI / nodes.size).toFloat()
        nodePositions.clear()
        for (i in nodes.indices) {
            val node = nodes[i]
            val angle = angleStep * i - (Math.PI / 2).toFloat()
            // Pull nodes further out based on hop count
            val dynamicRadius = radius * (0.4f + (node.hopCount * 0.3f).coerceAtMost(0.6f))
            val nx = cx + dynamicRadius * cos(angle.toDouble()).toFloat()
            val ny = cy + dynamicRadius * sin(angle.toDouble()).toFloat()
            nodePositions[node.nodeId] = PointF(nx, ny)
        }
        nodePositions[localNodeId] = PointF(cx, cy)

        // 2. Draw connection lines (Child to Parent/nextHop)
        for (node in nodes) {
            val pos = nodePositions[node.nodeId] ?: continue
            val parentId = if (node.nextHop == "self" || node.hopCount <= 1) localNodeId else node.nextHop
            
            // Find parent position (if parent is not in our list, link to center as fallback)
            val parentPos = nodePositions[parentId] ?: PointF(cx, cy)

            linePaint.color = if (node.isStale) staleColor else if (node.hopCount <= 1) connectedColor else reachableColor
            linePaint.pathEffect = if (node.hopCount > 1) DashPathEffect(floatArrayOf(8f, 8f), 0f) else null

            canvas.drawLine(pos.x, pos.y, parentPos.x, parentPos.y, linePaint)

            // Hop count label on line
            val midX = (pos.x + parentPos.x) / 2f
            val midY = (pos.y + parentPos.y) / 2f
            val hopText = "${node.hopCount}h"
            hopLabelPaint.textSize = 18f
            canvas.drawText(hopText, midX, midY - 6f, hopLabelPaint)
        }

        // 3. Draw peer nodes
        for (node in nodes) {
            val pos = nodePositions[node.nodeId] ?: continue
            
            nodePaint.color = when {
                node.isStale -> staleColor
                node.hopCount <= 1 -> connectedColor
                else -> reachableColor
            }

            canvas.drawCircle(pos.x, pos.y, nodeRadius, nodePaint)
            canvas.drawCircle(pos.x, pos.y, nodeRadius, nodeStrokePaint)

            // Node label (Device Name or Short ID)
            val label = if (node.deviceName != "Unknown") node.deviceName else node.nodeId.takeLast(6)
            textPaint.textSize = 18f
            canvas.drawText(label.take(8), pos.x, pos.y + 6f, textPaint)

            // Full-ish label below
            labelPaint.textSize = 20f
            val displayId = if (node.nodeId.length > 10) "…${node.nodeId.takeLast(10)}" else node.nodeId
            canvas.drawText(displayId, pos.x, pos.y + nodeRadius + 18f, labelPaint)
        }

        // 4. Draw center node (local)
        nodePaint.color = centerNodeColor
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = centerNodeColor
            alpha = 40
        }
        canvas.drawCircle(cx, cy, centerRadius + 8f, glowPaint)
        canvas.drawCircle(cx, cy, centerRadius, nodePaint)
        canvas.drawCircle(cx, cy, centerRadius, nodeStrokePaint)
        textPaint.textSize = 22f
        canvas.drawText("You", cx, cy + 8f, textPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            val ex = event.x
            val ey = event.y
            
            // Check if any peer node was clicked
            for ((nodeId, pos) in nodePositions) {
                if (nodeId == localNodeId) continue
                
                val dx = ex - pos.x
                val dy = ey - pos.y
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                if (dist < 40) { // Click radius
                    onNodeClick?.invoke(nodeId)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
