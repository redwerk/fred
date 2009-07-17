/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.MasterKeysFileTooBigException;
import freenet.node.MasterKeysFileTooShortException;
import freenet.node.MasterKeysWrongPasswordException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels;
import freenet.node.Node.AlreadySetPasswordException;
import freenet.node.SecurityLevels.FRIENDS_THREAT_LEVEL;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

/**
 * The security levels page.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class SecurityLevelsToadlet extends Toadlet {

	public static final int MAX_PASSWORD_LENGTH = 1024;
	private final NodeClientCore core;
	private final Node node;

	SecurityLevelsToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		this.node = node;
	}
	
	@Override
    public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if (!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n
			        .getString("Toadlet.unauthorized"));
			return;
		}
		
		String formPassword = request.getPartAsString("formPassword", 32);
		if((formPassword == null) || !formPassword.equals(core.formPassword)) {
			MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
			headers.put("Location", "/seclevels/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(request.isPartSet("seclevels")) {
			// Handle the security level changes.
			HTMLNode pageNode = null;
			HTMLNode content = null;
			HTMLNode ul = null;
			HTMLNode formNode = null;
			boolean changedAnything = false;
			String configName = "security-levels.networkThreatLevel";
			String confirm = "security-levels.networkThreatLevel.confirm";
			String tryConfirm = "security-levels.networkThreatLevel.tryConfirm";
			String networkThreatLevel = request.getPartAsString(configName, 128);
			NETWORK_THREAT_LEVEL newThreatLevel = SecurityLevels.parseNetworkThreatLevel(networkThreatLevel);
			if(newThreatLevel != null) {
				if(newThreatLevel != node.securityLevels.getNetworkThreatLevel()) {
					if(!request.isPartSet(confirm) && !request.isPartSet(tryConfirm)) {
						HTMLNode warning = node.securityLevels.getConfirmWarning(newThreatLevel, confirm);
						if(warning != null) {
							if(pageNode == null) {
								PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								pageNode = page.outer;
								content = page.content;
								formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
								ul = formNode.addChild("ul", "class", "config");
							}
							HTMLNode seclevelGroup = ul.addChild("li");

							seclevelGroup.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", configName, networkThreatLevel });
							HTMLNode infobox = seclevelGroup.addChild("div", "class", "infobox infobox-information");
							infobox.addChild("div", "class", "infobox-header", l10nSec("networkThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newThreatLevel)));
							HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
							infoboxContent.addChild(warning);
							infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", tryConfirm, "on" });
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newThreatLevel);
							changedAnything = true;
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newThreatLevel);
						changedAnything = true;
					}
				}
			}
			
			configName = "security-levels.friendsThreatLevel";
			confirm = "security-levels.friendsThreatLevel.confirm";
			tryConfirm = "security-levels.friendsThreatLevel.tryConfirm";
			String friendsThreatLevel = request.getPartAsString(configName, 128);
			FRIENDS_THREAT_LEVEL newFriendsLevel = SecurityLevels.parseFriendsThreatLevel(friendsThreatLevel);
			if(newFriendsLevel != null) {
				if(newFriendsLevel != node.securityLevels.getFriendsThreatLevel()) {
					if(!request.isPartSet(confirm) && !request.isPartSet(tryConfirm)) {
						HTMLNode warning = node.securityLevels.getConfirmWarning(newFriendsLevel, confirm);
						if(warning != null) {
							if(pageNode == null) {
								PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
								pageNode = page.outer;
								content = page.content;
								formNode = ctx.addFormChild(content, ".", "configFormSecLevels");
								ul = formNode.addChild("ul", "class", "config");
							}
							HTMLNode seclevelGroup = ul.addChild("li");

							seclevelGroup.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", configName, friendsThreatLevel });
							HTMLNode infobox = seclevelGroup.addChild("div", "class", "infobox infobox-information");
							infobox.addChild("div", "class", "infobox-header", l10nSec("friendsThreatLevelConfirmTitle", "mode", SecurityLevels.localisedName(newFriendsLevel)));
							HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
							infoboxContent.addChild(warning);
							infoboxContent.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", tryConfirm, "on" });
						} else {
							// Apply immediately, no confirm needed.
							node.securityLevels.setThreatLevel(newFriendsLevel);
							changedAnything = true;
						}
					} else if(request.isPartSet(confirm)) {
						// Apply immediately, user confirmed it.
						node.securityLevels.setThreatLevel(newFriendsLevel);
						changedAnything = true;
					}
				}
			}
			
			configName = "security-levels.physicalThreatLevel";
			confirm = "security-levels.physicalThreatLevel.confirm";
			tryConfirm = "security-levels.physicalThreatLevel.tryConfirm";
			String physicalThreatLevel = request.getPartAsString(configName, 128);
			PHYSICAL_THREAT_LEVEL newPhysicalLevel = SecurityLevels.parsePhysicalThreatLevel(physicalThreatLevel);
			PHYSICAL_THREAT_LEVEL oldPhysicalLevel = core.node.securityLevels.getPhysicalThreatLevel();
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "New physical threat level: "+newPhysicalLevel+" old = "+node.securityLevels.getPhysicalThreatLevel());
			if(newPhysicalLevel != null) {
				if(newPhysicalLevel == oldPhysicalLevel && newPhysicalLevel == PHYSICAL_THREAT_LEVEL.HIGH) {
					String password = request.getPartAsString("masterPassword", MAX_PASSWORD_LENGTH);
					String oldPassword = request.getPartAsString("oldPassword", MAX_PASSWORD_LENGTH);
					if(password != null && oldPassword != null && password.length() > 0 && oldPassword.length() > 0) {
						try {
							core.node.changeMasterPassword(oldPassword, password);
						} catch (MasterKeysWrongPasswordException e) {
							sendChangePasswordForm(ctx, true, false, newPhysicalLevel.name());
							return;
						} catch (MasterKeysFileTooBigException e) {
							SecurityLevelsToadlet.sendPasswordFileCorruptedPage(true, ctx, false, true);
							if(changedAnything)
								core.storeConfig();
							return;
						} catch (MasterKeysFileTooShortException e) {
							SecurityLevelsToadlet.sendPasswordFileCorruptedPage(false, ctx, false, true);
							if(changedAnything)
								core.storeConfig();
							return;
						}
					} else if(password != null || oldPassword != null) {
						sendChangePasswordForm(ctx, false, true, newPhysicalLevel.name());
						if(changedAnything)
							core.storeConfig();
						return;
					}
				}
				if(newPhysicalLevel != node.securityLevels.getPhysicalThreatLevel()) {
					// No confirmation for changes to physical threat level.
					if(newPhysicalLevel == PHYSICAL_THREAT_LEVEL.HIGH && node.securityLevels.getPhysicalThreatLevel() != newPhysicalLevel) {
						// Check for password
						String password = request.getPartAsString("masterPassword", MAX_PASSWORD_LENGTH);
						if(password != null && password.length() > 0) {
							try {
								if(oldPhysicalLevel == PHYSICAL_THREAT_LEVEL.NORMAL || oldPhysicalLevel == PHYSICAL_THREAT_LEVEL.LOW)
									core.node.changeMasterPassword("", password);
								else
									core.node.setMasterPassword(password, true);
							} catch (AlreadySetPasswordException e) {
								sendChangePasswordForm(ctx, false, false, newPhysicalLevel.name());
								return;
							} catch (MasterKeysWrongPasswordException e) {
								System.err.println("Wrong password!");
								PageNode page = ctx.getPageMaker().getPageNode(l10nSec("passwordPageTitle"), ctx);
								pageNode = page.outer;
								HTMLNode contentNode = page.content;
								
								content = ctx.getPageMaker().getInfobox("infobox-error", 
										l10nSec("passwordWrongTitle"), contentNode).
										addChild("div", "class", "infobox-content");
								
								SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, false, false, true, newPhysicalLevel.name());
								
								addBackToPhysicalSeclevelsLink(content);
								
								writeHTMLReply(ctx, 200, "OK", pageNode.generate());
								if(changedAnything)
									core.storeConfig();
								return;
							} catch (MasterKeysFileTooBigException e) {
								SecurityLevelsToadlet.sendPasswordFileCorruptedPage(true, ctx, false, true);
								if(changedAnything)
									core.storeConfig();
								return;
							} catch (MasterKeysFileTooShortException e) {
								SecurityLevelsToadlet.sendPasswordFileCorruptedPage(false, ctx, false, true);
								if(changedAnything)
									core.storeConfig();
								return;
							}
						} else {
							sendPasswordPage(ctx, password != null && password.length() == 0, newPhysicalLevel.name());
							if(changedAnything)
								core.storeConfig();
							return;
						}
					}
					if((newPhysicalLevel == PHYSICAL_THREAT_LEVEL.LOW || newPhysicalLevel == PHYSICAL_THREAT_LEVEL.NORMAL) &&
							oldPhysicalLevel == PHYSICAL_THREAT_LEVEL.HIGH) {
						// Check for password
						String password = request.getPartAsString("masterPassword", SecurityLevelsToadlet.MAX_PASSWORD_LENGTH);
						if(password != null && password.length() > 0) {
							// This is actually the OLD password ...
							try {
								core.node.changeMasterPassword(password, "");
							} catch (IOException e) {
								if(!core.node.getMasterPasswordFile().exists()) {
									// Ok.
									System.out.println("Master password file no longer exists, assuming this is deliberate");
								} else {
									System.err.println("Cannot change password as cannot write new passwords file: "+e);
									e.printStackTrace();
									String msg = "<html><head><title>"+l10nSec("cantWriteNewMasterKeysFileTitle")+
										"</title></head><body><h1>"+l10nSec("cantWriteNewMasterKeysFileTitle")+"</h1><p>"+l10nSec("cantWriteNewMasterKeysFile")+"<pre>";
									StringWriter sw = new StringWriter();
									PrintWriter pw = new PrintWriter(sw);
									e.printStackTrace(pw);
									pw.flush();
									msg = msg + sw.toString() + "</pre></body></html>";
									writeHTMLReply(ctx, 500, "Internal Error", msg);
									if(changedAnything)
										core.storeConfig();
									return;
								}
							} catch (MasterKeysWrongPasswordException e) {
								System.err.println("Wrong password!");
								PageNode page = ctx.getPageMaker().getPageNode(l10nSec("passwordForDecryptTitle"), ctx);
								pageNode = page.outer;
								HTMLNode contentNode = page.content;
								
								content = ctx.getPageMaker().getInfobox("infobox-error", 
										l10nSec("passwordWrongTitle"), contentNode).
										addChild("div", "class", "infobox-content");
									
								SecurityLevelsToadlet.generatePasswordFormPage(true, ctx.getContainer(), content, false, true, false, newPhysicalLevel.name());
								
								addBackToPhysicalSeclevelsLink(content);
								
								writeHTMLReply(ctx, 200, "OK", pageNode.generate());
								if(changedAnything)
									core.storeConfig();
								return;
							} catch (MasterKeysFileTooBigException e) {
								SecurityLevelsToadlet.sendPasswordFileCorruptedPage(true, ctx, false, true);
								if(changedAnything)
									core.storeConfig();
								return;
							} catch (MasterKeysFileTooShortException e) {
								SecurityLevelsToadlet.sendPasswordFileCorruptedPage(false, ctx, false, true);
								if(changedAnything)
									core.storeConfig();
								return;
							}
						} else if(core.node.getMasterPasswordFile().exists()) {
							// We need the old password
							PageNode page = ctx.getPageMaker().getPageNode(l10nSec("passwordForDecryptTitle"), ctx);
							pageNode = page.outer;
							HTMLNode contentNode = page.content;
							
							content = ctx.getPageMaker().getInfobox("infobox-error", 
									l10nSec("passwordForDecryptTitle"), contentNode).
									addChild("div", "class", "infobox-content");
							
							if(password != null && password.length() == 0) {
								content.addChild("p", l10nSec("passwordNotZeroLength"));
							}
							
							SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, false, true, false, newPhysicalLevel.name());
							
							addBackToPhysicalSeclevelsLink(content);
							
							writeHTMLReply(ctx, 200, "OK", pageNode.generate());
							if(changedAnything)
								core.storeConfig();
							return;
							
						}
						
					}
					node.securityLevels.setThreatLevel(newPhysicalLevel);
					changedAnything = true;
				}
			}
			
			if(changedAnything)
				core.storeConfig();
			
			if(pageNode != null) {
				formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
				formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else {
				MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
				headers.put("Location", "/seclevels/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
		} else {
			
			if(request.isPartSet("masterPassword")) {
				String masterPassword = request.getPartAsString("masterPassword", 1024);
				if(masterPassword.length() == 0) {
					sendPasswordPage(ctx, true, null);
					return;
				}
				System.err.println("Setting master password");
				try {
					node.setMasterPassword(masterPassword, false);
				} catch (AlreadySetPasswordException e) {
					System.err.println("Already set master password");
					Logger.error(this, "Already set master password");
					MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
					headers.put("Location", "/");
					ctx.sendReplyHeaders(302, "Found", headers, null, 0);
					return;
				} catch (MasterKeysWrongPasswordException e) {
					sendPasswordFormPage(true, ctx);
					return;
				} catch (MasterKeysFileTooBigException e) {
					sendPasswordFileCorruptedPage(true, ctx, false, false);
					return;
				} catch (MasterKeysFileTooShortException e) {
					sendPasswordFileCorruptedPage(false, ctx, false, false);
					return;
				}
				MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
				headers.put("Location", "/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			
			try {
				throw new RedirectException("/seclevels/");
			} catch (URISyntaxException e) {
				// Impossible
			}
		}
		
		MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
		headers.put("Location", "/");
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
	}
	
	/** Send a form asking the user to change the password. 
	 * @throws IOException 
	 * @throws ToadletContextClosedException */
	private void sendChangePasswordForm(ToadletContext ctx, boolean wrongPassword, boolean emptyPassword, String physicalSecurityLevel) throws ToadletContextClosedException, IOException {
		
		// Must set a password!
		PageNode page = ctx.getPageMaker().getPageNode(l10nSec("changePasswordTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error", 
				l10nSec("changePasswordTitle"), contentNode).
				addChild("div", "class", "infobox-content");
		
		if(emptyPassword) {
			content.addChild("p", l10nSec("passwordNotZeroLength"));
		}
		
		if(wrongPassword) {
			content.addChild("p", l10nSec("wrongOldPassword"));
		}
		
		HTMLNode form = ctx.addFormChild(content, path(), "changePasswordForm");
		
		addPasswordChangeForm(form);
		
		if(physicalSecurityLevel != null) {
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.physicalThreatLevel", physicalSecurityLevel });
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "true" });
		}
		addBackToPhysicalSeclevelsLink(content);
		
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}

	private void sendPasswordPage(ToadletContext ctx, boolean emptyPassword, String threatlevel) throws ToadletContextClosedException, IOException {
		
		// Must set a password!
		PageNode page = ctx.getPageMaker().getPageNode(l10nSec("setPasswordTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error", 
				l10nSec("setPasswordTitle"), contentNode).
				addChild("div", "class", "infobox-content");
		
		if(emptyPassword) {
			content.addChild("p", l10nSec("passwordNotZeroLength"));
		}
		
		SecurityLevelsToadlet.generatePasswordFormPage(false, ctx.getContainer(), content, false, false, true, threatlevel);
		
		addBackToPhysicalSeclevelsLink(content);
		
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void addBackToPhysicalSeclevelsLink(HTMLNode content) {
		// TODO Auto-generated method stub
		
	}

	@Override
    public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("SecurityLevelsToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(core.alerts.createSummary());
		
		drawSecurityLevelsPage(contentNode, ctx);
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	private void drawSecurityLevelsPage(HTMLNode contentNode, ToadletContext ctx) {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", l10nSec("title"));
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, ".", "configFormSecLevels");
		// Network security level
		formNode.addChild("div", "class", "configprefix", l10nSec("networkThreatLevelShort"));
		HTMLNode ul = formNode.addChild("ul", "class", "config");
		HTMLNode seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("networkThreatLevel"));
		
		NETWORK_THREAT_LEVEL networkLevel = node.securityLevels.getNetworkThreatLevel();
		
		String controlName = "security-levels.networkThreatLevel";
		for(NETWORK_THREAT_LEVEL level : NETWORK_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == networkLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("networkThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.networkThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.networkThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
		}
		
		// Friends security level
		formNode.addChild("div", "class", "configprefix", l10nSec("friendsThreatLevelShort"));
		ul = formNode.addChild("ul", "class", "config");
		seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("friendsThreatLevel"));
		
		FRIENDS_THREAT_LEVEL friendsLevel = node.securityLevels.getFriendsThreatLevel();
		
		controlName = "security-levels.friendsThreatLevel";
		for(FRIENDS_THREAT_LEVEL level : FRIENDS_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == friendsLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("friendsThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.friendsThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.friendsThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
		}
		
		// Physical security level
		formNode.addChild("div", "class", "configprefix", l10nSec("physicalThreatLevelShort"));
		ul = formNode.addChild("ul", "class", "config");
		seclevelGroup = ul.addChild("li");
		seclevelGroup.addChild("#", l10nSec("physicalThreatLevel"));
		
		PHYSICAL_THREAT_LEVEL physicalLevel = node.securityLevels.getPhysicalThreatLevel();
		
		controlName = "security-levels.physicalThreatLevel";
		for(PHYSICAL_THREAT_LEVEL level : PHYSICAL_THREAT_LEVEL.values()) {
			HTMLNode input;
			if(level == physicalLevel) {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "checked", "name", "value" }, new String[] { "radio", "on", controlName, level.name() });
			} else {
				input = seclevelGroup.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", controlName, level.name() });
			}
			input.addChild("b", l10nSec("physicalThreatLevel.name."+level));
			input.addChild("#", ": ");
			L10n.addL10nSubstitution(input, "SecurityLevels.physicalThreatLevel.choice."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			HTMLNode inner = input.addChild("p").addChild("i");
			L10n.addL10nSubstitution(inner, "SecurityLevels.physicalThreatLevel.desc."+level, new String[] { "bold", "/bold" }, new String[] { "<b>", "</b>" });
			if(level == PHYSICAL_THREAT_LEVEL.HIGH) {
				if(node.securityLevels.getPhysicalThreatLevel() == level) {
					addPasswordChangeForm(inner);
				} else {
					// Add password form
					HTMLNode p = inner.addChild("p");
					p.addChild("label", "for", "passwordBox", l10nSec("setPassword"));
					p.addChild("input", new String[] { "id", "type", "name" }, new String[] { "passwordBox", "text", "masterPassword" });
				}
			}
		}
		
		// FIXME implement the rest, it should be very similar to the above.
		
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "on" });
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
	}

	private void addPasswordChangeForm(HTMLNode inner) {
		HTMLNode table = inner.addChild("table", "border", "0");
		HTMLNode row = table.addChild("tr");
		HTMLNode cell = row.addChild("td");
		cell.addChild("label", "for", "oldPasswordBox", l10nSec("oldPasswordLabel"));
		cell = row.addChild("td");
		cell.addChild("input", new String[] { "id", "type", "name", "size" }, new String[] { "oldPasswordBox", "text", "oldPassword", "100" });
		row = table.addChild("tr");
		cell = row.addChild("td");
		cell.addChild("label", "for", "newPasswordBox", l10nSec("newPasswordLabel"));
		cell = row.addChild("td");
		cell.addChild("input", new String[] { "id", "type", "name", "size" }, new String[] { "passwordBox", "text", "masterPassword", "100" });
		HTMLNode p = inner.addChild("p");
		p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "changePassword", l10nSec("changePasswordButton") });
	}

	@Override
	public String path() {
		return "/seclevels/";
	}

	@Override
	public String supportedMethods() {
		return "GET, POST";
	}

	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

	private static String l10nSec(String key) {
		return L10n.getString("SecurityLevels."+key);
	}
	
	private static String l10nSec(String key, String pattern, String value) {
		return L10n.getString("SecurityLevels."+key, pattern, value);
	}
	
	/** Send a page asking what to do when the master password file has been corrupted. 
	 * @param forSecLevels The user has tried to change the security levels.
	 * @param forFirstTimeWizard The user has tried to set a password in the first-time wizard on the physical security levels page.
	 * If neither of the above are set, the user is just trying to unlock an existing master keys file, which sadly has been corrupted. :(
	 * @param ctx */
	static void sendPasswordFileCorruptedPage(boolean tooBig, ToadletContext ctx, boolean forSecLevels, boolean forFirstTimeWizard) {
		// OPTIONS:
		// Set a new password. (Save what has already been loaded, dump the rest; need to indicate what would be lost?).
		// Do nothing. (Let the user restore the file).
		// Don't set that seclevel?
		// TODO Auto-generated method stub
		
	}

	/** Send a page asking for the master password.
	 * @param wasWrong If true, we want the master password because the user entered the wrong 
	 * password.
	 * @param ctx 
	 * @throws IOException 
	 * @throws ToadletContextClosedException 
	 */
	private void sendPasswordFormPage(boolean wasWrong, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10nSec("passwordPageTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error", 
				wasWrong ? l10nSec("passwordWrongTitle") : l10nSec("enterPasswordTitle"), contentNode).
				addChild("div", "class", "infobox-content");
		
		generatePasswordFormPage(wasWrong, ctx.getContainer(), content, false, false, false, null);
		
		addHomepageLink(content);
		
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	public static void generatePasswordFormPage(boolean wasWrong, ToadletContainer ctx, HTMLNode content, boolean forFirstTimeWizard, boolean forDowngrade, boolean forUpgrade, String physicalSecurityLevel) {
		if(forDowngrade) {
			if(!wasWrong)
				content.addChild("#", l10nSec("passwordForDecrypt"));
			else
				content.addChild("#", l10nSec("passwordForDecryptPasswordWrong"));
		} else if(wasWrong)
			content.addChild("#", l10nSec("passwordWrong"));
		else if(forUpgrade)
			content.addChild("#", l10nSec("setPassword"));
		else
			content.addChild("#", l10nSec("enterPassword"));
		
		HTMLNode form = forFirstTimeWizard ? ctx.addFormChild(content, "/wizard/", "masterPasswordForm") :
			ctx.addFormChild(content, "/seclevels/", "masterPasswordForm");
		form.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "masterPassword", "100" });
		if(physicalSecurityLevel != null) {
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "security-levels.physicalThreatLevel", physicalSecurityLevel });
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "seclevels", "true" });
		}
		form.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10nSec("passwordSubmit") });
	}

}
