# Functional Design Specification: CatNav Android Application

## 1. System Overview
The CatNav Android application is the user interface for the Cat Tracker system. The application connects directly to the Heltec V3 gateway through a local Wi-Fi network. The application uses an asynchronous REST API at `http://cat-gateway.local`. The gateway keeps a maximum of 1024 location records in volatile memory. The CatNav application stores all historical tracking data permanently in local storage.

## 2. User Interface and Visual Theme
The application must use a Modern Light Mode design system.
*   **Visual Style:** The interface uses a light background style.
*   **Contrast and Readability:** Text and primary icons use dark colors on light backgrounds to ensure high contrast and clear readability outdoors.
*   **Accent Color:** Interactive buttons, active toggles, and trace lines use a distinct accent color.
*   **Visual Structure:** Layout containers use rounded card designs, subtle depth effects, and clean typography to separate functional sections.
*   **Design Freedom:** The specification defines no explicit hexadecimal color codes. The visual designer retains full freedom to choose the light mode palette.
*   **Theme Mode:** The application forces Light Mode as the default visual theme.

## 3. Device Management and Network Discovery
The application allows the user to add and manage cat trackers.
*   **Manual Entry:** The user can add a new device by entering a 32-bit tracker ID.
*   **Gateway Discovery:** The application can request the list of connected devices from the gateway. The application sends a `GET` request to `/api/v1/trackers`.
*   **mDNS Network Resolution:** The application must discover the gateway on the local Wi-Fi network using standard Android network service discovery (mDNS) to resolve `cat-gateway.local`. The application settings must also include a manual IP fallback option if local mDNS resolution fails.

## 4. Power State and Sleep Operations
The application controls the operational state of connected devices.
*   **Manual Control:** The application provides controls to send `WAKE` and `SLEEP` commands. The user can select one device or select multiple devices for bulk action.
*   **User Input Requirement:** The application sends `WAKE` or `SLEEP` commands only after direct user input.
*   **Wake Latency Handling:** A dormant device requires time up to `DORMANT_SLEEP_MINUTES` to activate. The application polls `/api/v1/jobs/{id}` to track command progress.
*   **User Notification:** When the gateway confirms that a device wakes up, the application sends a push notification to the user phone.

## 5. Data Synchronization and Database
The application operates without a central cloud server and stores data locally.
*   **Automatic Fetch:** When the user opens the application, the application automatically queues a `FETCH` command to retrieve new data from active devices, provided the local data for that device is older than a configurable threshold to prevent gateway job queue overload.
*   **Manual Fetch Button:** The interface includes a manual button to request new data at any time.
*   **Local Database:** The application uses a local SQLite database to store all historical locations.
*   **XML Data Transfer:** The application provides functions to export the local database to an XML file. The application also permits importing data from an XML file.

## 6. Battery Alerts and Charge Requests
The application alerts the user when a tracker battery is empty.
*   **Alert Listener:** When a tracker battery falls below `CRITICAL_BATTERY_MILLIVOLTS`, the tracker sends a `CHARGE_REQUEST`.
*   **Server-Sent Events:** The application monitors the gateway event stream at `/api/v1/events`.
*   **Background Delivery:** The application must ensure reliable reception of Server-Sent Events (SSE) and delivery of local notifications even when the application is running in the background or when OS battery optimization controls are active.

## 7. Map and Visualization
The map screen displays location history and movement patterns.
*   **Trace Mode:** The map displays location points connected by lines.
    *   Directional arrows along the lines indicate the travel direction.
    *   A prominent visual marker highlights the latest position.
    *   When the user taps a location point, the exact timestamp appears in a small card at the bottom of the screen.
*   **Heat Map Mode:** The map displays a heat map layer. The layer highlights areas where the cat spends the most time.
*   **Time Filter:** The map interface includes a time filter control. The filter limits the data shown in both Trace Mode and Heat Map Mode.
*   **Battery Indicator:** The map screen shows a battery level indicator in percent.
    *   The percentage uses a standard state-of-charge (SOC) curve for a 3.7V 1000mAh Lithium-Ion Polymer 102050 battery.
    *   The application dynamically recalculates the full SOC curve when `CRITICAL_BATTERY_MILLIVOLTS` is modified, setting 0% SOC to match the configured critical voltage limit.

## 8. Settings Configuration
The application includes a dedicated settings screen.
*   **Configuration Scope:** The user can view and change settings for the CatNav application, the gateway, and connected trackers.
*   **Tracker Configuration:** The settings interface supports changing all configuration parameters as defined in the Communication Protocol (`gateway_protocol.md`).
*   **Active Device Restriction:** Updating configuration settings is restricted exclusively to devices that are currently active (awake). The application must not send configuration updates to sleeping/dormant devices.
*   **Local Storage:** The application saves configuration values in local storage. The application does not request configuration data from the gateway every time the user opens the settings screen.
*   **Sync Option:** The settings screen includes a manual synchronization button. The user can trigger a sync sequence (`GET_CONFIG` and `SET_CONFIG`) for an active single device or all active devices.