# DataForge AI: Advanced Datasheet Management System

**DataForge AI** is a comprehensive desktop application engineered to resolve the inefficiencies associated with managing unformatted, scattered hardware and engineering datasheets.

## Development Methodology

This application was conceptualized and fully developed utilizing advanced prompt engineering techniques, without prior traditional software development experience. The entire system architecture—encompassing the Java backend, frontend interface, and AI integrations—was constructed by strategically directing AI models. This project serves as a proof of concept for leveraging artificial intelligence to build functional, production-ready software solutions.

## Core Features

* **Advanced Search Mechanism:** Enables instantaneous, query-based access to specific datasheets within local directories.
* **Automated Standardization via Gemini API:** Scans and analyzes legacy datasheets of varying formats, converting them into a unified, standardized data structure.
* **In-App Editing and PDF Export:** Allows users to correct data discrepancies directly through the interface and export the finalized documents as clean PDFs.
* **Manual File Management:** Supports the manual creation of new datasheets, hierarchical file organization, and localized note-taking for specific components.
* **AI Catalog Assistant:** Features an integrated analytical chatbot that cross-references locally stored datasheets with web data to provide precise, context-aware answers to technical inquiries.
* **Professional User Interface:** Provides an optimized, accessible user experience with comprehensive Dark and Light theme support.

## Technical Architecture

* **Backend:** Java (Maven, JavaFX)
* **Frontend:** HTML, Vanilla JavaScript, CSS
* **AI Integration:** Google Gemini API

## Installation and Execution

1. Clone this repository to your local environment.
2. Navigate to the `src/main/java/com/pilsan/datasheet/AppSecrets.java` file and insert your valid Gemini API Key.
3. Execute the application using the provided initialization scripts:
   * **macOS / Linux:** Open the terminal in the project directory and execute `sh start.sh` or `mvn clean javafx:run`.
   * **Windows:** Execute the `start.bat` script.
