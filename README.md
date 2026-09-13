# AI-Powered Datasheet Manager & Catalog Assistant

**Datasheet AI Manager** is a comprehensive desktop application developed to address the inefficiency of managing unformatted and scattered hardware and engineering datasheets.

**Development Approach:**
This application was conceptualized and developed without any traditional software engineering background. The entire architecture, including the Java backend, frontend interface, and AI integrations, was constructed exclusively through advanced prompt engineering. It demonstrates the viability of utilizing artificial intelligence to build functional, production-ready software systems.

## Core Features

* **Advanced Search Mechanism:** Enables instant access to specific datasheets within the local directory.
* **Automated Standardization via Gemini API:** Scans and analyzes legacy datasheets of varying formats, converting them into a unified, standardized structure.
* **In-App Editing and PDF Export:** Allows users to correct errors in existing datasheets directly through the interface and export the finalized documents as PDFs.
* **Manual File Management:** Supports the manual creation of new datasheets, file organization via folders, and taking specialized notes on specific components.
* **AI Catalog Assistant:** Features an integrated analytical chatbot that cross-references locally stored datasheets with web data to provide precise answers to technical inquiries.
* **Professional User Interface:** Provides an optimized experience with both Dark and Light mode capabilities.

## Technical Stack

* **Backend:** Java (Maven, JavaFX)
* **Frontend:** HTML, Vanilla JavaScript, CSS
* **AI Integration:** Google Gemini API

## Installation and Execution

1. Clone this repository to your local machine.
2. Navigate to the `src/main/java/com/pilsan/datasheet/AppSecrets.java` file and insert your valid Gemini API Key.
3. Execute the application using the provided scripts:
   * **macOS / Linux:** Open the terminal in the project directory and execute `sh start.sh` or `mvn clean javafx:run`.
   * **Windows:** Execute the `start.bat` file.
