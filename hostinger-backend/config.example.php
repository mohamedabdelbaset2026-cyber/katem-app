<?php
/**
 * Katem Secure Media Gateway Configuration (Hostinger Shared Hosting)
 *
 * SECURITY SETUP INSTRUCTIONS:
 * 1. Copy this file to `config.php` OUTSIDE `public_html` (e.g. `/home/uXXXXXX/katem_secure/config.php`).
 * 2. Create the private upload folder OUTSIDE `public_html` (e.g. `/home/uXXXXXX/katem_private_storage`).
 * 3. Download a Firebase Service Account JSON (read-only Firestore permission) and place it OUTSIDE `public_html`.
 * 4. NEVER store Hostinger FTP/cPanel passwords or Service Account JSON inside the Android APK.
 */

return [
    // Your Firebase Project ID (from app/google-services.json)
    'firebase_project_id' => 'gen-lang-client-0995716392',

    // Provisioned Firestore Named Database ID (from firebase_applet_config.xml)
    'firestore_database_id' => 'ai-studio-android-katem-a68b2583-0ae9-4f7c-941d-d46944a489f8',

    // Absolute path OUTSIDE public_html where media files are stored securely
    'private_storage_dir' => dirname(__DIR__, 2) . '/katem_private_storage',

    // Absolute path OUTSIDE public_html to your Firebase Service Account JSON
    // Used strictly server-side to verify `conversations/{conversationId}.memberUids`
    'service_account_json' => dirname(__DIR__, 2) . '/katem_secure/firebase-service-account.json',

    // Public HTTPS base URL where upload.php & download.php live
    'public_api_base_url' => 'https://your-hostinger-domain.com/katem-api',

    // Maximum upload size in bytes (25 MB)
    'max_file_size_bytes' => 25 * 1024 * 1024,

    // Per-user upload rate limit (max uploads per 60 seconds)
    'rate_limit_per_minute' => 20,

    // Strictly allowlisted MIME types -> safe extensions (blocks .php/.phtml/.sh execution!)
    'allowed_mime_map' => [
        'image/jpeg'      => 'jpg',
        'image/png'       => 'png',
        'image/webp'      => 'webp',
        'video/mp4'       => 'mp4',
        'video/quicktime' => 'mov',
        'audio/mp4'       => 'm4a',
        'audio/aac'       => 'aac',
        'audio/mpeg'      => 'mp3',
        'audio/ogg'       => 'ogg',
        'application/pdf' => 'pdf',
        'text/plain'      => 'txt',
        'application/msword' => 'doc',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document' => 'docx',
    ],
];
