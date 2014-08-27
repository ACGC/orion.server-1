/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.core.metastore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrate the MetaStore from the version one of the simple metadata store (SimpleMetaStoreV1) to 
 * version two of the simple metadata store (SimpleMetaStoreV2).
 * 
 * @author Anthony Hunter
 */
public class SimpleMetaStoreMigration {

	/**
	 * The first version of the Simple Meta Store was version 4 introduced for Orion 4.0.
	 */
	public final static int VERSION4 = 4;

	/**
	 * The second version of the Simple Meta Store was version 6 introduced for Orion 4.0.
	 */
	public final static int VERSION6 = 6;

	private Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$

	/**
	 * Perform the migration for the provided user folder.
	 * @param rootLocation The root location of the metadata store.
	 * @param userMetaFolder The users metadata folder.
	 * @throws JSONException
	 */
	public void doMigration(File rootLocation, File userMetaFolder) throws JSONException {
		String userId = userMetaFolder.getName();
		logger.info("Migration: Start migrating user " + userId + " to the latest (version " + SimpleMetaStore.VERSION + ")");
		int oldVersion = readOrionVersion(userMetaFolder, SimpleMetaStore.USER);
		File userMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(userMetaFolder, SimpleMetaStore.USER);
		File[] files = userMetaFolder.listFiles();
		int directoryCount = 0;
		for (int i = 0; i < files.length; i++) {
			File next = files[i];
			if (next.isFile()) {
				if (next.equals(userMetaFile)) {
					// skip the user.json
					continue;
				} else if (oldVersion == VERSION6 && next.getName().endsWith(SimpleMetaStoreUtil.METAFILE_EXTENSION)) {
					// process a {workspaceId}.json or {projectName}.json
					File parent = next.getParentFile();
					String name = next.getName().substring(0, next.getName().length() - SimpleMetaStoreUtil.METAFILE_EXTENSION.length());
					updateOrionVersion(parent, name);
					updateProjectContentLocation(parent, name);
				} else {
					// User folder contains invalid metadata: orphan file
					SimpleMetaStoreUtil.archive(rootLocation, next);
				}
			} else if (next.isDirectory()) {
				if (oldVersion == VERSION4) {
					// process a workspace folder in /serverworkspace/an/anthony
					updateWorkspaceFolder(rootLocation, next);
					directoryCount++;
				} else {
					// process a workspace folder in /serverworkspace/an/anthony
					directoryCount++;
				}
			}
		}
		if ((oldVersion == VERSION4 || oldVersion == VERSION6) && directoryCount > 1) {
			mergeMultipleWorkspaces(directoryCount, userMetaFolder);
		}
		updateOrionVersion(userMetaFolder, SimpleMetaStore.USER);
		logger.info("Migration: Finished migrating user " + userId);
	}

	/**
	 * Determine if migration for the provided metadata is required.
	 * @param jsonObject the Orion metadata in JSON format.
	 * @return true if migration is required.
	 * @throws JSONException
	 */
	public boolean isMigrationRequired(JSONObject jsonObject) throws JSONException {
		if (!jsonObject.has(SimpleMetaStore.ORION_VERSION)) {
			return true;
		}
		int version = jsonObject.getInt(SimpleMetaStore.ORION_VERSION);
		if (version != SimpleMetaStore.VERSION) {
			return true;
		}
		return false;
	}

	/**
	 * In version 4 and version 6 it as possible to have multiple workspaces, merge them in the default workspace
	 * and delete the extra workspaces.
	 * @param count The number of folders under the user, should be one for the workspace folder.
	 * @param userMetaFolder The user metadata folder.
	 * @throws JSONException
	 */
	private void mergeMultipleWorkspaces(int count, File userMetaFolder) throws JSONException {
		JSONObject userJSON = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, SimpleMetaStore.USER);
		JSONArray workspaceIds = userJSON.getJSONArray("WorkspaceIds");
		boolean changedUserJSON = false;
		boolean changedWorkspaceJSON = false;
		if (workspaceIds.length() == count) {
			// the extra folder(s) in the user folder are valid workspaces, merge them into one
			String firstWorkspaceId = null;
			JSONObject firstWorkspaceJSON = null;
			File firstWorkspaceFolder = null;
			for (int i = 0; i < workspaceIds.length(); i++) {
				String workspaceId = workspaceIds.getString(i);
				String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
				if (SimpleMetaStoreUtil.isMetaFile(userMetaFolder, workspaceId) && SimpleMetaStoreUtil.isMetaFolder(userMetaFolder, encodedWorkspaceName)) {
					JSONObject workspaceJSON = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, workspaceId);
					File workspaceFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);
					if (firstWorkspaceId == null) {
						// the first workspace is the master
						firstWorkspaceId = workspaceId;
						firstWorkspaceJSON = workspaceJSON;
						firstWorkspaceFolder = workspaceFolder;
						continue;
					} else {
						JSONArray projectNames = workspaceJSON.getJSONArray("ProjectNames");
						for (int p = 0; p < projectNames.length(); p++) {
							String projectName = projectNames.getString(p);
							if (SimpleMetaStoreUtil.isMetaFolder(workspaceFolder, projectName) && SimpleMetaStoreUtil.isMetaFile(userMetaFolder, projectName)) {
								// project is in the default location, move project folder and then update project metadata
								File originalProjectFolder = SimpleMetaStoreUtil.retrieveMetaFolder(workspaceFolder, projectName);
								SimpleMetaStoreUtil.moveMetaFolder(workspaceFolder, projectName, firstWorkspaceFolder, projectName);
								File newProjectFolder = SimpleMetaStoreUtil.retrieveMetaFolder(firstWorkspaceFolder, projectName);
								logger.info("Migration: Moved project folder: " + originalProjectFolder.toString() + " to " + newProjectFolder.toString());
								JSONObject projectJSON = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, projectName);
								String contentLocation = newProjectFolder.toURI().toString();
								// remove trailing slash from the contentLocation 
								contentLocation = contentLocation.substring(0, contentLocation.length() - 1);
								String encodedContentLocation = SimpleMetaStoreUtil.encodeProjectContentLocation(contentLocation);
								projectJSON.put("ContentLocation", encodedContentLocation);
								projectJSON.put("WorkspaceId", firstWorkspaceId);
								SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, projectName, projectJSON);
								File updatedMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(userMetaFolder, projectName);
								logger.info("Migration: Updated project metadata: " + updatedMetaFile.toString() + " with new workspaceId " + firstWorkspaceId);
							}
							JSONArray firstWorkspaceProjectNames = firstWorkspaceJSON.getJSONArray("ProjectNames");
							firstWorkspaceProjectNames.put(projectName);
							logger.info("Migration: Updated workspace metadata: updated workspace " + firstWorkspaceId + " with new project " + projectName);
							changedWorkspaceJSON = true;
						}
					}
					SimpleMetaStoreUtil.deleteMetaFolder(userMetaFolder, encodedWorkspaceName, true);
					logger.info("Migration: Updated workspace metadata: deleted multiple workspace folder of " + workspaceId + " at " + workspaceFolder);
					File workspaceMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(userMetaFolder, workspaceId);
					SimpleMetaStoreUtil.deleteMetaFile(userMetaFolder, workspaceId);
					logger.info("Migration: Updated workspace metadata: deleted multiple workspace file at " + workspaceMetaFile);
					changedUserJSON = true;
				} else {
					// an invalid workspace folder
					logger.info("Migration: Workspace folder contains invalid metadata: orphan folder " + workspaceId); //$NON-NLS-1$
				}
			}
			if (firstWorkspaceId != null) {
				if (changedUserJSON) {
					updateUserJson(userMetaFolder, userJSON, firstWorkspaceId);
					logger.info("Migration: Updated user metadata: user has one workspace " + firstWorkspaceId);
				}
				if (changedWorkspaceJSON) {
					File updatedMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(userMetaFolder, firstWorkspaceId);
					SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, firstWorkspaceId, firstWorkspaceJSON);
					logger.info("Migration: Updated workspace metadata: updated workspace metadata at " + updatedMetaFile.toString());
				}
			}
		}
	}

	private void moveProjectJsonFile(File folder, String projectName) {
		File userMetaFolder = folder.getParentFile();
		File oldProjectMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(folder, projectName);
		File newProjectMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(userMetaFolder, projectName);
		if (newProjectMetaFile.exists()) {
			logger.error("Migration: Duplicate project metadata file at " + newProjectMetaFile.toString()); //$NON-NLS-1$
			return;
		}
		SimpleMetaStoreUtil.moveMetaFile(folder, projectName, userMetaFolder, projectName);
		logger.info("Migration: Old project metadata file " + oldProjectMetaFile.toString() + " has been moved to " + newProjectMetaFile.toString());
	}

	private void moveWorkspaceJsonFile(File folder) throws JSONException {
		File parent = folder.getParentFile();
		File oldWorkspaceMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(folder, SimpleMetaStore.WORKSPACE);
		JSONObject workspaceJSON = SimpleMetaStoreUtil.readMetaFile(folder, SimpleMetaStore.WORKSPACE);
		if (!workspaceJSON.has("UniqueId")) {
			logger.error("Migration: Workspace metadata is missing UniqueId " + oldWorkspaceMetaFile.toString()); //$NON-NLS-1$
			return;
		}
		String workspaceId = workspaceJSON.getString("UniqueId");
		File newWorkspaceMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(parent, workspaceId);
		if (newWorkspaceMetaFile.exists()) {
			logger.error("Migration: Duplicate workspace metadata file at " + newWorkspaceMetaFile.toString()); //$NON-NLS-1$
			return;
		}
		SimpleMetaStoreUtil.moveMetaFile(folder, SimpleMetaStore.WORKSPACE, parent, workspaceId);
		logger.info("Migration: Old workspace metadata file " + oldWorkspaceMetaFile.toString() + " has been moved to " + newWorkspaceMetaFile.toString());
	}

	/**
	 * Update the Orion version in the provided file and folder.
	 * @param parent The parent folder containing the metadata (JSON) file.
	 * @param name The name of the file without the ".json" extension.
	 * @return The previous version that was in the metadata file.
	 * @throws JSONException 
	 */
	private void updateOrionVersion(File parent, String name) throws JSONException {
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(parent, name);
		int oldVersion = -1;
		if (jsonObject.has(SimpleMetaStore.ORION_VERSION)) {
			oldVersion = jsonObject.getInt(SimpleMetaStore.ORION_VERSION);
		}
		jsonObject.put(SimpleMetaStore.ORION_VERSION, SimpleMetaStore.VERSION);
		SimpleMetaStoreUtil.updateMetaFile(parent, name, jsonObject);
		File metaFile = SimpleMetaStoreUtil.retrieveMetaFile(parent, name);
		String oldVersionStr = (oldVersion == -1) ? "UNKNOWN" : Integer.toString(oldVersion);
		logger.info("Migration: Updated Orion version from version " + oldVersionStr + " to version " + SimpleMetaStore.VERSION + " in metadata file: " + metaFile.toString());
	}

	/**
	 * Update the Orion version in the provided file and folder.
	 * @param parent The parent folder containing the metadata (JSON) file.
	 * @param name The name of the file without the ".json" extension.
	 * @return The previous version that was in the metadata file.
	 * @throws JSONException 
	 */
	private int readOrionVersion(File parent, String name) throws JSONException {
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(parent, name);
		int oldVersion = -1;
		if (jsonObject.has(SimpleMetaStore.ORION_VERSION)) {
			oldVersion = jsonObject.getInt(SimpleMetaStore.ORION_VERSION);
		}
		return oldVersion;
	}

	/**
	 * Update the user metadata file with the new single workspace and user rights.
	 * @param userMetaFolder The user metadata folder.
	 * @param userJSON The current user metadata.
	 * @param workspaceId the single workspace. 
	 */
	private void updateUserJson(File userMetaFolder, JSONObject userJSON, String workspaceId) throws JSONException {
		JSONArray workspaceIds = new JSONArray();
		workspaceIds.put(workspaceId);
		userJSON.put("WorkspaceIds", workspaceIds);

		JSONObject properties = userJSON.getJSONObject("Properties");
		JSONArray userRights = new JSONArray();
		JSONObject userRight = new JSONObject();
		userRight.put("Method", 15);
		String usersRight = "/users/";
		userRight.put("Uri", usersRight.concat(userMetaFolder.getName()));
		userRights.put(userRight);

		userRight = new JSONObject();
		userRight.put("Method", 15);
		String workspaceRight = "/workspace/";
		userRight.put("Uri", workspaceRight.concat(workspaceId));
		userRights.put(userRight);

		userRight = new JSONObject();
		userRight.put("Method", 15);
		userRight.put("Uri", workspaceRight.concat(workspaceId).concat("/*"));
		userRights.put(userRight);

		userRight = new JSONObject();
		userRight.put("Method", 15);
		String fileRight = "/file/";
		userRight.put("Uri", fileRight.concat(workspaceId));
		userRights.put(userRight);

		userRight = new JSONObject();
		userRight.put("Method", 15);
		userRight.put("Uri", fileRight.concat(workspaceId).concat("/*"));
		userRights.put(userRight);

		properties.put("UserRights", userRights);
		userJSON.put("Properties", properties);

		SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, SimpleMetaStore.USER, userJSON);
	}

	/**
	 * Update a Simple Meta Store version 4 workspace folder to the latest version. 
	 * @param rootLocation The root location of the metadata store.
	 * @param workspaceMetaFolder A workspace folder.
	 * @throws JSONException
	 */
	private void updateWorkspaceFolder(File rootLocation, File workspaceMetaFolder) throws JSONException {
		if (!SimpleMetaStoreUtil.isMetaFile(workspaceMetaFolder, SimpleMetaStore.WORKSPACE)) {
			// the folder does not have a workspace.json, so archive the folder.
			SimpleMetaStoreUtil.archive(rootLocation, workspaceMetaFolder);
			return;
		}
		File workspaceMetaFile = SimpleMetaStoreUtil.retrieveMetaFile(workspaceMetaFolder, SimpleMetaStore.WORKSPACE);
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(workspaceMetaFolder, SimpleMetaStore.WORKSPACE);
		if (!jsonObject.has("ProjectNames")) {
			logger.error("Migration: Workspace metadata is missing ProjectNames " + workspaceMetaFile.toString()); //$NON-NLS-1$
			return;
		}
		JSONArray projectNames = jsonObject.getJSONArray("ProjectNames");
		List<String> projectNameList = new ArrayList<String>();
		for (int i = 0; i < projectNames.length(); i++) {
			projectNameList.add(projectNames.getString(i));
		}
		File[] files = workspaceMetaFolder.listFiles();
		for (int i = 0; i < files.length; i++) {
			File next = files[i];
			if (next.equals(workspaceMetaFile)) {
				// skip the workspace.json
				continue;
			} else if (next.isDirectory()) {
				// process project folder in /serverworkspace/an/anthony/workspace
				if (!projectNameList.contains(next.getName())) {
					// Workspace folder contains invalid metadata: archive orphan project folder
					SimpleMetaStoreUtil.archive(rootLocation, next);
				}
			} else if (next.isFile()) {
				// process project folder in /serverworkspace/an/anthony/workspace
				if (next.getName().endsWith(SimpleMetaStoreUtil.METAFILE_EXTENSION)) {
					String name = next.getName().substring(0, next.getName().length() - SimpleMetaStoreUtil.METAFILE_EXTENSION.length());
					if (projectNameList.contains(name)) {
						// Update the project metadata
						updateOrionVersion(workspaceMetaFolder, name);
						updateProjectContentLocation(workspaceMetaFolder, name);
						moveProjectJsonFile(workspaceMetaFolder, name);
					}
				}
			}
		}
		updateOrionVersion(workspaceMetaFolder, SimpleMetaStore.WORKSPACE);
		moveWorkspaceJsonFile(workspaceMetaFolder);
	}

	/**
	 * Update the ContentLocation in the provided project metadata file and folder.
	 * @param parent The parent folder containing the metadata (JSON) file.
	 * @param name The name of the file without the ".json" extension.
	 * @throws JSONException 
	 */
	private void updateProjectContentLocation(File parent, String name) throws JSONException {
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(parent, name);
		if (jsonObject.has("ContentLocation")) {
			String contentLocation = jsonObject.getString("ContentLocation");
			String encodedContentLocation = SimpleMetaStoreUtil.encodeProjectContentLocation(contentLocation);
			jsonObject.put("ContentLocation", encodedContentLocation);
			SimpleMetaStoreUtil.updateMetaFile(parent, name, jsonObject);
			File metaFile = SimpleMetaStoreUtil.retrieveMetaFile(parent, name);
			logger.info("Migration: Updated the ContentLocation in metadata file: " + metaFile.toString());
		}
	}
}
