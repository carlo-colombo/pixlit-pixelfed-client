# Implementation Plan - Update Post Fetching and Tag Processing

This plan outlines the changes required to update how posts are fetched, how tags are processed and displayed, and how the UI handles the visibility of fetched posts.

## Proposed Changes

### [PixelfedRepository](file:///home/carlo/projects/my-pixelfed/app/src/main/java/com/example/pixelfed/data/repository/PixelfedRepository.kt)

#### [MODIFY] [PixelfedRepository.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/com/example/pixelfed/data/repository/PixelfedRepository.kt)
- Update `getUserTopTagsAndPosts` to:
    - Fetch the current user's account ID via `verifyCredentials`.
    - Fetch statuses using `getUserStatuses` from the `PixelfedApi`.
    - Implement fallback to `getStaticStatuses()` (assets JSON) if the API call fails or returns no statuses.
- Update `extractTopTagsFromStatuses` to:
    - Keep track of tag counts.
    - Select the top 20 most used tags.
    - Sort these 20 tags alphabetically.
    - Format each tag as `#tagname (count)`.

### [UploadScreen](file:///home/carlo/projects/my-pixelfed/app/src/main/java/com/example/pixelfed/ui/upload/UploadScreen.kt)

#### [MODIFY] [UploadScreen.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/com/example/pixelfed/ui/upload/UploadScreen.kt)
- Introduce a `selectedTab` state to toggle between "Upload" and "Debug".
- Add a `TabRow` at the top of the screen content to switch between these tabs.
- Move the "Fetched Posts" list and other debug-related info into the "Debug" tab.
- Update `insertTagAtCursor` to handle the new formatted tag string (extracting only the tag name before insertion).
- Ensure the "Upload" tab is the default view.

## Verification Plan

### Automated Tests
- I will check if there are existing tests for `PixelfedRepository` and update them if necessary.
- I'll run `app:assembleDebug` to ensure the project still builds.

### Manual Verification
- Deploy the app to the device.
- Verify that the "Upload" tab is shown by default and doesn't contain the fetched posts list.
- Navigate to the "Debug" tab and verify the fetched posts are shown there.
- Verify the "Top Tags" show up to 20 tags, sorted alphabetically, with counts in brackets.
- Test clicking a tag to ensure it's correctly inserted into the caption (without the count).
