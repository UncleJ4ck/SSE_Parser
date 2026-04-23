# sse_parser.py

from burp import IBurpExtender, IProxyListener, IInterceptedProxyMessage, ITab
from javax.swing import (
    JPanel, JTextArea, JScrollPane, JTable, JLabel,
    JSplitPane, BorderFactory, JButton, JCheckBox
)
from javax.swing.table import AbstractTableModel, DefaultTableCellRenderer
from javax.swing.border import EmptyBorder, CompoundBorder
from javax.swing.event import ListSelectionListener
from javax.swing import ListSelectionModel
from java.awt import BorderLayout, Dimension, Color, Font
from java.text import SimpleDateFormat
from java.util import Date
from java.lang import Runnable, Thread, Object
import json
import traceback

#
# Example minimal renderer. You can customize row colors, etc.
#
class CellRenderer(DefaultTableCellRenderer):
    def __init__(self, extender):
        self.extender = extender

    def getTableCellRendererComponent(self, table, value, isSelected, hasFocus, row, column):
        component = DefaultTableCellRenderer.getTableCellRendererComponent(
            self, table, value, isSelected, hasFocus, row, column
        )
        if not isSelected:
            # Use your dark color
            component.setBackground(Color(43, 43, 43))
            component.setForeground(Color(220, 220, 220))
        else:
            # Selected row highlight color
            component.setBackground(table.getSelectionBackground())
            component.setForeground(table.getSelectionForeground())

        return component


class BurpExtender(IBurpExtender, IProxyListener, ITab):

    def registerExtenderCallbacks(self, callbacks):
        self._callbacks = callbacks
        self._helpers = callbacks.getHelpers()

        callbacks.setExtensionName("SSE parser Extension")

        # Register as a Proxy listener (incremental SSE streaming)
        callbacks.registerProxyListener(self)

        # SSE data structures
        self.sse_buffers = {}
        self.sse_message_refs = set()

        # Table / UI data
        self.events = []

        # Initialize UI
        self.setupUI()
        callbacks.addSuiteTab(self)

        self.log("SSE parser Extension initialized with chunk-based SSE parsing.")

    #
    # Updated setupUI() based on your snippet
    #
    def setupUI(self):
        # Main panel with BorderLayout
        self.panel = JPanel(BorderLayout())

        #
        # Top Panel (buttons only, label removed)
        #
        topPanel = JPanel(BorderLayout())

        # We'll create a small panel to the right for our buttons
        buttonPanel = JPanel()
        clearAllButton = JButton("Clear All Data")
        clearAllButton.addActionListener(lambda e: self.clearAllData())
        buttonPanel.add(clearAllButton)

        clearLogsButton = JButton("Clear Logs")
        clearLogsButton.addActionListener(lambda e: self.clearLogs())
        buttonPanel.add(clearLogsButton)

        topPanel.add(buttonPanel, BorderLayout.EAST)

        self.panel.add(topPanel, BorderLayout.NORTH)

        #
        # Center Panel: split top (table) and bottom (text area)
        #
        centerPanel = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        centerPanel.setResizeWeight(0.7)

        # Events Table
        self.eventsTableModel = EventsTableModel(self)
        self.eventsTable = JTable(self.eventsTableModel)
        self.eventsTable.setAutoCreateRowSorter(True)
        self.eventsTable.setDefaultRenderer(Object, CellRenderer(self))
        self.eventsTable.setSelectionMode(0)  # single selection

        scrollPane = JScrollPane(self.eventsTable)
        centerPanel.setTopComponent(scrollPane)

        # Event Details Viewer (text area)
        self.eventViewer = JTextArea()
        self.eventViewer.setEditable(False)
        self.eventViewer.setLineWrap(True)
        self.eventViewer.setWrapStyleWord(True)
        eventScrollPane = JScrollPane(self.eventViewer)

        centerPanel.setBottomComponent(eventScrollPane)

        self.panel.add(centerPanel, BorderLayout.CENTER)

        #
        # Bottom Panel for Logs
        #
        bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(JLabel("Logs:"), BorderLayout.NORTH)

        self.logArea = JTextArea()
        self.logArea.setEditable(False)
        self.logArea.setLineWrap(True)
        self.logArea.setWrapStyleWord(True)

        logScrollPane = JScrollPane(self.logArea)
        logScrollPane.setPreferredSize(Dimension(800, 150))
        bottomPanel.add(logScrollPane, BorderLayout.CENTER)

        self.panel.add(bottomPanel, BorderLayout.SOUTH)

        #
        # Table selection listener -> show details
        #
        self.eventsTable.getSelectionModel().addListSelectionListener(
            lambda e: self.showEventDetails(e) if not e.getValueIsAdjusting() else None
        )

    #
    # ITab
    #
    def getTabCaption(self):
        return "SSE parser"

    def getUiComponent(self):
        return self.panel

    #
    # Logging utility
    #
    def log(self, message):
        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        msg = "[{}] {}\n".format(timestamp, message)
        self.logArea.append(msg)
        self.logArea.setCaretPosition(self.logArea.getDocument().getLength())

    #
    # IProxyListener
    #
    def processProxyMessage(self, messageIsRequest, message):
        # We only handle responses
        if messageIsRequest:
            return

        messageInfo = message.getMessageInfo()
        if not messageInfo:
            return
        raw_response = messageInfo.getResponse()
        if not raw_response:
            return

        msg_ref = message.getMessageReference()

        analyzed = self._helpers.analyzeResponse(raw_response)
        headers = analyzed.getHeaders()
        body_offset = analyzed.getBodyOffset()
        body_bytes = raw_response[body_offset:]
        body_str = self._helpers.bytesToString(body_bytes)

        # Detect SSE content-type
        if msg_ref not in self.sse_message_refs:
            for header in headers:
                if header.lower().startswith("content-type:") and "text/event-stream" in header.lower():
                    self.sse_message_refs.add(msg_ref)
                    self.log("Detected SSE stream on messageRef: {}".format(msg_ref))
                    break

        # If not SSE, forward normally
        if msg_ref not in self.sse_message_refs:
            message.setInterceptAction(IInterceptedProxyMessage.ACTION_FOLLOW_RULES)
            return

        # Accumulate partial chunk data
        partial_buf = self.sse_buffers.get(msg_ref, "")
        partial_buf += body_str

        lines = partial_buf.split("\n")
        if not partial_buf.endswith("\n"):
            self.sse_buffers[msg_ref] = lines[-1]
            lines = lines[:-1]
        else:
            self.sse_buffers[msg_ref] = ""

        # Parse SSE lines
        current_event = {}
        for line in lines:
            line = line.strip("\r")
            if line.startswith(":"):
                # SSE comment
                continue
            elif line.startswith("event:"):
                current_event["event"] = line[len("event:"):].strip()
            elif line.startswith("data:"):
                piece = line[len("data:"):].strip()
                if "data" not in current_event:
                    current_event["data"] = piece
                else:
                    current_event["data"] += "\n" + piece
            elif line.startswith("id:"):
                current_event["id"] = line[len("id:"):].strip()
            elif line.strip() == "":
                # blank line => new SSE event
                if current_event:
                    current_event["session"] = "SSE Session"
                    self.addEvent(current_event)
                    current_event = {}

        message.setInterceptAction(IInterceptedProxyMessage.ACTION_FOLLOW_RULES)

    #
    # Adding events to the table
    #
    def addEvent(self, event):
        self.events.append(event)
        self.eventsTableModel.fireTableDataChanged()
        self.log(
            "[SSE Event] Session: {}, Event: {}, ID: {}, Data: {}".format(
                event.get('session', 'Uncategorized'),
                event.get('event', 'N/A'),
                event.get('id', 'N/A'),
                event.get('data', 'N/A')
            )
        )

    #
    # Show selected event in the text area
    #
    def showEventDetails(self, e):
        row = self.eventsTable.getSelectedRow()
        if row != -1:
            row = self.eventsTable.convertRowIndexToModel(row)
            event_data = self.events[row]
            display_text = (
                "Session: {}\nEvent: {}\nID: {}\nData:\n{}"
                .format(
                    event_data.get('session', 'Uncategorized'),
                    event_data.get('event', 'N/A'),
                    event_data.get('id', 'N/A'),
                    event_data.get('data', 'N/A')
                )
            )
            self.eventViewer.setText(display_text)

    #
    # Clear data and logs
    #
    def clearAllData(self):
        # Clear events in the table
        self.events = []
        self.eventsTableModel.fireTableDataChanged()
        # Also clear the event details text area
        self.eventViewer.setText("")
        # Log it
        self.log("Cleared all event data.")

    def clearLogs(self):
        self.logArea.setText("")


#
# Table Model for SSE events
#
class EventsTableModel(AbstractTableModel):
    def __init__(self, extender):
        self.extender = extender
        self.columnNames = ["Time", "Session", "Event", "ID", "Data"]

    def getRowCount(self):
        return len(self.extender.events)

    def getColumnCount(self):
        return len(self.columnNames)

    def getColumnName(self, col):
        return self.columnNames[col]

    def getValueAt(self, rowIndex, colIndex):
        event = self.extender.events[rowIndex]
        if colIndex == 0:
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        elif colIndex == 1:
            return event.get('session', 'Uncategorized')
        elif colIndex == 2:
            return event.get('event', '')
        elif colIndex == 3:
            return event.get('id', '')
        elif colIndex == 4:
            data = event.get('data', '')
            if isinstance(data, dict):
                data_str = json.dumps(data, indent=2)
            else:
                data_str = str(data)
            return data_str[:50] + "..." if len(data_str) > 50 else data_str
        return ""

    def isCellEditable(self, rowIndex, colIndex):
        return False

    def getColumnClass(self, columnIndex):
        return str