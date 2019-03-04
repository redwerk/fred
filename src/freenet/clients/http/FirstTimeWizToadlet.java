/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version).
 * See http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;

import java.io.IOException;
import java.net.URI;

/**
 * A new first time wizard aimed to ease the configuration of the node.
 * https://freenet.mantishub.io/view.php?id=6020 - Redesign first time wizard UI
 */
public class FirstTimeWizToadlet extends Toadlet {

	static final String TOADLET_URL = "/wiz/";

	private final NodeClientCore core;

	FirstTimeWizToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.core = core;
		Config config = node.config;

		addWizardConfiguration(config);
	}

	@Override
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
		 throws ToadletContextClosedException, IOException, RedirectException {
		if(!ctx.checkFullAccess(this))
			return;

		PageNode page =
			 ctx.getPageMaker().getPageNode(l10n("title"), ctx,
					new PageMaker.RenderParameters().renderNavigationLinks(false).renderStatus(false));
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		addHtmlForm(contentNode);
		this.writeHTMLReply(ctx, 200, "OK", null, pageNode.generate());
	}

	@Override
	public String path() {
		return TOADLET_URL;
	}

	private void addHtmlForm(HTMLNode content) {
		HTMLNode form = content.addChild("form", "method", "post");

		HTMLNode networkSecurityFieldset = form.addChild("fieldset");

		networkSecurityFieldset.addChild("legend", l10n("networkSecurity"));
		networkSecurityFieldset.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"knowSomeone", "knowSomeone", "checkbox"});
		networkSecurityFieldset.addChild("label", "for", "knowSomeone",
			 l10n("iKnowSomeoneWhoRunsFreenet"));
		HTMLNode nsfNoDarknet = networkSecurityFieldset.addChild("div", "id", "noDarknet");
		nsfNoDarknet.addChild("p", l10n("iKnowSomeoneWhoRunsFreenetDescription"));
		HTMLNode nsfCheckDarknet = networkSecurityFieldset.addChild("div",
			 new String[] {"id", "hidden"},
			 new String[] {"checkDarknet", "true"});
		HTMLNode nsfCheckDarknetP = nsfCheckDarknet.addChild("p");
		nsfCheckDarknetP.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"connectToStrangers", "connectToStrangers", "checkbox"});
		nsfCheckDarknetP.addChild("label", "for", "connectToStrangers",
			 l10n("darknetWantToConnectToUntrusted"));

		HTMLNode bandwidthFieldset = form.addChild("fieldset");

		bandwidthFieldset.addChild("legend", l10n("bandwidth"));
		bandwidthFieldset.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"haveMonthlyLimit", "haveMonthlyLimit", "checkbox"});
		bandwidthFieldset.addChild("label", "for", "haveMonthlyLimit",
			 l10n("iHaveMonthlyBandwidthLimit"));
		// TODO: Once input type="number" is more widely supported it could be used for these inputs.
		HTMLNode bandwidthFieldsetMonthlyLimitChecked = bandwidthFieldset.addChild("div",
			 new String[] {"id", "hidden"},
			 new String[] {"monthlyLimitChecked", "true"});
		HTMLNode bandwidthFieldsetMonthlyLimitCheckedP = bandwidthFieldsetMonthlyLimitChecked.addChild("p");
		bandwidthFieldsetMonthlyLimitCheckedP
			 .addChild("span", l10n("bandwidthLetFreenetUseUpTo"));
		bandwidthFieldsetMonthlyLimitCheckedP.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"monthlyLimit", "monthlyLimit", "number"});
		bandwidthFieldsetMonthlyLimitCheckedP.addChild("span", l10n("bandwidthGiBPerMonth"));
		HTMLNode bandwidthFieldsetMonthlyLimitUnchecked =
			 bandwidthFieldset.addChild("div", "id", "monthlyLimitUnchecked");
		bandwidthFieldsetMonthlyLimitUnchecked.addChild("p", l10n("bandwidthLetFreenetUseUpTo"));
		bandwidthFieldsetMonthlyLimitUnchecked.addChild("input",
			 new String[] {"id", "name", "type", "value"},
			 new String[] {"downLimit", "downLimit", "number", "900"});
		bandwidthFieldsetMonthlyLimitUnchecked.addChild("span", "KiB/s download");
		bandwidthFieldsetMonthlyLimitUnchecked.addChild("input",
			 new String[] {"id", "name", "type", "value"},
			 new String[] {"upLimit", "upLimit", "number", "300"});
		bandwidthFieldsetMonthlyLimitUnchecked.addChild("span", "KiB/s upload");
		HTMLNode bandwidthFieldsetMonthlyLimitUncheckedP =
			 bandwidthFieldsetMonthlyLimitChecked.addChild("p");
		bandwidthFieldsetMonthlyLimitUncheckedP.addChild("h4", l10n("commonInternetConnectionSpeeds"));
		HTMLNode bandwidthFieldsetMonthlyLimitUncheckedPTable =
			 bandwidthFieldsetMonthlyLimitUncheckedP.addChild("table");
		HTMLNode bandwidthFieldsetMonthlyLimitUncheckedPTableTr =
			 bandwidthFieldsetMonthlyLimitUncheckedPTable.addChild("tr");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("th", l10n("connectionType"));
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("th", l10n("downloadLimit"));
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("th", l10n("uploadLimit"));
		// TODO: This will be a template loop over a container of common speeds provided by the node.
		//  .type, .down, .up per row.
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr =
			 bandwidthFieldsetMonthlyLimitUncheckedPTable.addChild("tr");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", l10n("detected"));
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "1024 KiB/s");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "100 KiB/s");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr =
			 bandwidthFieldsetMonthlyLimitUncheckedPTable.addChild("tr");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", l10n("4megabits"));
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "256 KiB/s (2Mbps)");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "16 KiB/s");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr =
			 bandwidthFieldsetMonthlyLimitUncheckedPTable.addChild("tr");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", l10n("6megabits"));
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "384 KiB/s (3Mbps)");
		bandwidthFieldsetMonthlyLimitUncheckedPTableTr.addChild("td", "16 KiB/s");

		HTMLNode storageFieldset = form.addChild("fieldset");
		storageFieldset.addChild("legend", l10n("storage"));
		HTMLNode storageFieldsetP = storageFieldset.addChild("p");
		storageFieldsetP.addChild("span", l10n("storage.give"));
		// TODO: Populated by the node with some percentage of free space.
		// TODO: Possibly attach this number with a slider of percentage of free space.
		storageFieldsetP.addChild("input",
			 new String[] {"id", "name", "type", "value"},
			 new String[] {"storage", "storage", "number", "30"});
		storageFieldsetP.addChild("span", l10n("storage.toFreenet"));
		storageFieldsetP = storageFieldset.addChild("p");
		storageFieldsetP.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"hasFDE", "FDE", "checkbox"});
		storageFieldsetP.addChild("label", "for", "hasFDE",
			 l10n("storage.iUseFullDiskEncryption"));
		HTMLNode storageFieldsetDiv =
			 storageFieldset.addChild("div", "id", "encryptionDisabled");
		storageFieldsetDiv.addChild("p", l10n("storage.freenetWillNotEncryptItsOnDiskStorage"));
		storageFieldset.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"setPassword", "setPassword", "checkbox"});
		storageFieldset.addChild("label", "for", "setPassword",
			 l10n("storage.iWantFreenetToRequirePasswordWhenStarts"));
		HTMLNode storageFieldsetPass =
			 storageFieldset.addChild("div", "id", "passwordboxcontainer");
		HTMLNode storageFieldsetPassDiv = storageFieldsetPass.addChild("div",
			 new String[] {"id", "hidden"},
			 new String[] {"givePassword", "true"});
		HTMLNode storageFieldsetPassDivP = storageFieldsetPassDiv.addChild("p");
		storageFieldsetPassDivP
			 .addChild("label", "for", "password", l10n("storage.password"));
		storageFieldsetPassDivP.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"password", "password", "password"});
		storageFieldsetPassDivP = storageFieldsetPassDiv.addChild("p");
		storageFieldsetPassDivP
			 .addChild("label", "for", "confirmPassword", l10n("storage.confirmPassword"));
		storageFieldsetPassDivP.addChild("input",
			 new String[] {"id", "name", "type"},
			 new String[] {"confirmPassword", "confirmPassword", "password"});

		content.addChild("script", "src", "/static/js/firsttimewizard.js");
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("FirstTimeWizToadlet." + key);
	}

	private void addWizardConfiguration(Config config) {
		SubConfig wizConfig = config.createSubConfig("firstTimeWiz");

		wizConfig.register("loadUPnPPlugin", true, 0,
			 true, false, "FirstTimeWizToadlet.loadUPnPPlugin",
			 "FirstTimeWizToadlet.loadUPnPPluginLong", createLoadUPnPPluginCallback());
		loadUPnPPlugin = wizConfig.getBoolean("loadUPnPPlugin");

		wizConfig.register("enableAutoUpdater", true, 1,
			 true, false, "FirstTimeWizardToadlet.enableAutoUpdater",
			 "FirstTimeWizardToadlet.enableAutoUpdaterLong", createEnableAutoUpdaterCallback());
		enableAutoUpdater = wizConfig.getBoolean("enableAutoUpdater");

		wizConfig.finishedInitialization();
	}

	private boolean loadUPnPPlugin;

	private BooleanCallback createLoadUPnPPluginCallback() {
		return new BooleanCallback() {
			@Override
			public Boolean get() {
				return loadUPnPPlugin;
			}

			@Override
			public void set(Boolean value) {
				loadUPnPPlugin = value;
			}
		};
	}

	private boolean enableAutoUpdater;

	private BooleanCallback createEnableAutoUpdaterCallback() {
		return new BooleanCallback() {
			@Override
			public Boolean get() {
				return enableAutoUpdater;
			}

			@Override
			public void set(Boolean value) {
				enableAutoUpdater = value;
			}
		};
	}
}
