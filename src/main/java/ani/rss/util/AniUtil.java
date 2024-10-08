package ani.rss.util;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.*;
import cn.hutool.http.HttpConnection;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class AniUtil {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final List<Ani> ANI_LIST = new Vector<>();

    /**
     * 获取订阅配置文件
     *
     * @return
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + "ani.json");
    }

    /**
     * 加载订阅
     */
    public static void load() {
        File configFile = getAniFile();

        if (!configFile.exists()) {
            FileUtil.writeUtf8String(JSONUtil.formatJsonStr(GSON.toJson(ANI_LIST)), configFile);
        }
        String s = FileUtil.readUtf8String(configFile);
        JsonArray jsonElements = GSON.fromJson(s, JsonArray.class);
        for (JsonElement jsonElement : jsonElements) {
            Ani ani = GSON.fromJson(jsonElement, Ani.class);
            Ani newAni = new Ani();
            newAni.setEnable(true)
                    .setOva(false)
                    .setThemoviedbName("")
                    .setGlobalExclude(false)
                    .setCurrentEpisodeNumber(0)
                    .setTotalEpisodeNumber(0);
            BeanUtil.copyProperties(ani, newAni, CopyOptions
                    .create()
                    .setIgnoreNullValue(true));
            ANI_LIST.add(newAni);
        }
        log.debug("加载订阅 共{}项", ANI_LIST.size());


        // 处理旧数据
        for (Ani ani : ANI_LIST) {
            String subgroup = StrUtil.blankToDefault(ani.getSubgroup(), "");
            ani.setSubgroup(subgroup);
            try {
                String cover = ani.getCover();
                if (!ReUtil.contains("http(s*)://", cover)) {
                    continue;
                }
                cover = AniUtil.saveJpg(cover);
                ani.setCover(cover);
                AniUtil.sync();
            } catch (Exception e) {
                log.error(e.getMessage());
                log.debug(e.getMessage(), e);
            }
        }
    }

    /**
     * 将订阅配置保存到磁盘
     */
    public static void sync() {
        File configFile = getAniFile();
        String json = GSON.toJson(ANI_LIST);
        FileUtil.writeUtf8String(JSONUtil.formatJsonStr(json), configFile);
        log.debug("保存订阅 {}", configFile);
    }

    /**
     * 获取动漫信息
     *
     * @param url
     * @return
     */
    public static Ani getAni(String url) {
        int season = 1;
        String title = "无";

        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        String bangumiId = "", subgroupid = "";
        for (String k : decodeParamMap.keySet()) {
            String v = decodeParamMap.get(k);
            if (k.equalsIgnoreCase("bangumiId")) {
                bangumiId = v;
            }
            if (k.equalsIgnoreCase("subgroupid")) {
                subgroupid = v;
            }
        }

        Assert.notBlank(bangumiId, "不支持的链接, 请使用mikan对应番剧的字幕组RSS");
        Assert.notBlank(subgroupid, "不支持的链接, 请使用mikan对应番剧的字幕组RSS");

        String s = HttpReq.get(url, true)
                .thenFunction(HttpResponse::body);
        Document document = XmlUtil.readXML(s);
        Node channel = document.getElementsByTagName("channel").item(0);
        NodeList childNodes = channel.getChildNodes();

        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node item = childNodes.item(i);
            String nodeName = item.getNodeName();
            if (nodeName.equals("title")) {
                title = ReUtil.replaceAll(item.getTextContent(), "^Mikan Project - ", "").trim();
            }
        }

        String seasonReg = "第(.+)季";
        if (ReUtil.contains(seasonReg, title)) {
            season = Convert.chineseToNumber(ReUtil.get(seasonReg, title, 1));
            title = ReUtil.replaceAll(title, seasonReg, "").trim();
        }

        Ani ani = new Ani();

        String finalSubgroupid = subgroupid;
        HttpReq.get(URLUtil.getHost(URLUtil.url(url)) + "/Home/Bangumi/" + bangumiId, true)
                .then(res -> {
                    org.jsoup.nodes.Document html = Jsoup.parse(res.body());

                    // 获取封面
                    Elements elementsByClass = html.select(".bangumi-poster");
                    Element element = elementsByClass.get(0);
                    String style = element.attr("style");
                    String image = style.replace("background-image: url('", "").replace("');", "");
                    HttpConnection httpConnection = (HttpConnection) ReflectUtil.getFieldValue(res, "httpConnection");
                    String saveJpg = saveJpg(URLUtil.getHost(httpConnection.getUrl()) + image);
                    ani.setCover(saveJpg);

                    // 获取字幕组
                    Elements subgroupTexts = html.select(".subgroup-text");
                    for (Element subgroupText : subgroupTexts) {
                        String id = subgroupText.attr("id");
                        if (!id.equalsIgnoreCase(finalSubgroupid)) {
                            continue;
                        }
                        String ownText = subgroupText.ownText().trim();
                        if (StrUtil.isNotBlank(ownText)) {
                            ani.setSubgroup(ownText);
                            continue;
                        }
                        ani.setSubgroup(subgroupText.selectFirst("a").text().trim());
                    }
                });

        title = title.replace("剧场版", "").trim();

        String themoviedbName = getThemoviedbName(title);

        ani.setOffset(0)
                .setUrl(url.trim())
                .setSeason(season)
                .setTitle(title)
                .setThemoviedbName(themoviedbName)
                .setEnable(true)
                .setCurrentEpisodeNumber(0)
                .setTotalEpisodeNumber(0)
                .setOva(false)
                .setGlobalExclude(true)
                .setExclude(List.of("720"));

        Config config = ConfigUtil.CONFIG;
        Boolean titleYear = config.getTitleYear();
        Boolean tmdb = config.getTmdb();

        AniUtil.getBangumiInfo(ani, true, true, titleYear);

        if (StrUtil.isNotBlank(themoviedbName) && tmdb) {
            ani.setTitle(themoviedbName);
        }

        log.debug("获取到动漫信息 {}", JSONUtil.formatJsonStr(GSON.toJson(ani)));

        List<Item> items = getItems(ani, s);
        log.debug("获取到视频 共{}个", items.size());
        if (items.isEmpty() || ani.getOva()) {
            return ani;
        }
        // 自动推断剧集偏移
        if (config.getOffset()) {
            int offset = -(items.stream()
                    .map(Item::getEpisode)
                    .min(Comparator.comparingInt(i -> i))
                    .get() - 1);
            log.debug("自动获取到剧集偏移为 {}", offset);
            ani.setOffset(offset);
        }
        return ani;
    }

    public static String saveJpg(String coverUrl) {
        File jpgFile = new File(URLUtil.toURI(coverUrl).getPath());
        String dir = jpgFile.getParentFile().getName();
        String filename = jpgFile.getName();
        File configDir = ConfigUtil.getConfigDir();
        FileUtil.mkdir(configDir + "/files/" + dir);
        File file = new File(configDir + "/files/" + dir + "/" + filename);
        if (file.exists()) {
            return dir + "/" + filename;
        }
        HttpReq.get(coverUrl, true)
                .then(res -> FileUtil.writeFromStream(res.bodyStream(), file));
        return dir + "/" + filename;
    }

    /**
     * 获取视频列表
     *
     * @param ani
     * @param xml
     * @return
     */
    public static List<Item> getItems(Ani ani, String xml) {
        String title = ani.getTitle();
        List<String> exclude = ani.getExclude();
        Boolean ova = ani.getOva();

        int offset = ani.getOffset();
        int season = ani.getSeason();
        List<Item> items = new ArrayList<>();

        Document document = XmlUtil.readXML(xml);
        Node channel = document.getElementsByTagName("channel").item(0);
        NodeList childNodes = channel.getChildNodes();
        Config config = ConfigUtil.CONFIG;
        List<String> globalExcludeList = config.getExclude();
        Boolean globalExclude = ani.getGlobalExclude();

        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node item = childNodes.item(i);
            String nodeName = item.getNodeName();
            if (!nodeName.equals("item")) {
                continue;
            }
            String itemTitle = "";
            String torrent = "";
            String length = "";

            NodeList itemChildNodes = item.getChildNodes();
            for (int j = 0; j < itemChildNodes.getLength(); j++) {
                Node itemChild = itemChildNodes.item(j);
                String itemChildNodeName = itemChild.getNodeName();
                if (itemChildNodeName.equals("title")) {
                    itemTitle = itemChild.getTextContent();
                }
                if (itemChildNodeName.equals("enclosure")) {
                    NamedNodeMap attributes = itemChild.getAttributes();
                    torrent = attributes.getNamedItem("url").getNodeValue();
                    length = attributes.getNamedItem("length").getNodeValue();
                }
            }

            String size = "0MB";
            try {
                if (StrUtil.isNotBlank(length)) {
                    Double l = Long.parseLong(length) / 1024.0 / 1024;
                    size = NumberUtil.decimalFormat("0.00", l) + "MB";
                }
            } catch (Exception e) {
                log.warn(e.getMessage());
            }

            Item newItem = new Item()
                    .setTitle(itemTitle)
                    .setReName(itemTitle)
                    .setTorrent(torrent)
                    .setSize(size);

            // 进行过滤
            if (exclude.stream().anyMatch(s -> ReUtil.contains(s, newItem.getTitle()))) {
                continue;
            }

            // 全局排除
            if (globalExclude) {
                if (globalExcludeList.stream().anyMatch(s -> ReUtil.contains(s, newItem.getTitle()))) {
                    continue;
                }
            }
            items.add(newItem);
        }

        if (ova) {
            return items;
        }

        String s = "(.*|\\[.*])( -? \\d+|\\[\\d+]|\\[\\d+.?[vV]\\d]|第\\d+[话話集]|\\[第?\\d+[话話集]]|\\[\\d+.?END]|[Ee][Pp]?\\d+)(.*)";

        items = items.stream()
                .filter(item -> {
                    try {
                        String itemTitle = item.getTitle();
                        String e = ReUtil.get(s, itemTitle, 2);
                        String episode = ReUtil.get("\\d+", e, 0);
                        if (StrUtil.isBlank(episode)) {
                            return false;
                        }
                        item.setEpisode(Integer.parseInt(episode) + offset);
                        item
                                .setReName(
                                        StrFormatter.format("{} S{}E{}",
                                                title,
                                                String.format("%02d", season),
                                                String.format("%02d", item.getEpisode()))
                                );
                        return true;
                    } catch (Exception e) {
                        log.error("解析rss视频集次出现问题");
                        log.debug(e.getMessage(), e);
                    }
                    return false;
                }).collect(Collectors.toList());
        return CollUtil.distinct(items, Item::getReName, true);
    }

    /**
     * 获取视频列表
     *
     * @param ani
     * @return
     */
    public static synchronized List<Item> getItems(Ani ani) {
        String url = ani.getUrl();
        String s = HttpReq.get(url, true)
                .thenFunction(HttpResponse::body);
        return getItems(ani, s);
    }

    /**
     * 校验参数
     *
     * @param ani
     */
    public static void verify(Ani ani) {
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();
        Integer season = ani.getSeason();
        Integer offset = ani.getOffset();
        String title = ani.getTitle();
        Assert.notBlank(url, "RSS URL 不能为空");
        if (Objects.isNull(exclude)) {
            ani.setExclude(new ArrayList<>());
        }
        Assert.notNull(season, "季不能为空");
        Assert.notBlank(title, "标题不能为空");
        Assert.notNull(offset, "集数偏移不能为空");
    }

    public static void getBangumiInfo(Ani ani, Boolean ova, Boolean totalEpisode, Boolean titleYear) {
        String url = ani.getUrl();
        Integer totalEpisodeNumber = ObjectUtil.defaultIfNull(ani.getTotalEpisodeNumber(), 0);
        ani.setTotalEpisodeNumber(totalEpisodeNumber);
        if (totalEpisode) {
            if (totalEpisodeNumber > 0 && !ova) {
                return;
            }
        }

        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        String bangumiId = "";
        for (String k : decodeParamMap.keySet()) {
            String v = decodeParamMap.get(k);
            if (k.equalsIgnoreCase("bangumiId")) {
                bangumiId = v;
            }
        }

        HttpReq.get(URLUtil.getHost(URLUtil.url(url)) + "/Home/Bangumi/" + bangumiId, true)
                .then(res -> {
                    org.jsoup.nodes.Document document = Jsoup.parse(res.body());
                    Elements bangumiInfos = document.select(".bangumi-info");
                    String bgmUrl = "";
                    String year = "";
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            bgmUrl = bangumiInfo.select("a").get(0).attr("href");
                        }
                        if (string.startsWith("放送开始：")) {
                            try {
                                String dateReg = "(\\d+)/(\\d+)/(\\d+)";
                                year = ReUtil.get(dateReg, string, 3);
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                        }
                    }
                    String title = ani.getTitle();
                    String themoviedbName = ani.getThemoviedbName();
                    if (titleYear && StrUtil.isNotBlank(year)) {
                        ani.setTitle(StrFormatter.format("{} ({})", title, year));
                        if (StrUtil.isNotBlank(themoviedbName)) {
                            ani.setThemoviedbName(StrFormatter.format("{} ({})", themoviedbName, year));
                        }
                    }

                    if (StrUtil.isBlank(bgmUrl) && !ova) {
                        return;
                    }

                    HttpReq.get(bgmUrl, true)
                            .then(response -> {
                                org.jsoup.nodes.Document parse = Jsoup.parse(response.body());
                                Element inner = parse.selectFirst(".subject_tag_section");
                                if (Objects.nonNull(inner)) {
                                    Elements aa = inner.select("a");
                                    List<String> tags = new ArrayList<>();
                                    for (Element a : aa) {
                                        Element span = a.selectFirst("span");
                                        if (Objects.isNull(span)) {
                                            continue;
                                        }
                                        tags.add(span.ownText());
                                    }

                                    if (ova) {
                                        if (!tags.contains("TV") && (tags.contains("OVA") || tags.contains("剧场版"))) {
                                            ani.setOva(true);
                                        }
                                    }
                                }
                                for (Element element : parse.select(".tip")) {
                                    String s = element.ownText();
                                    if (!s.equals("话数:")) {
                                        continue;
                                    }
                                    try {
                                        Integer ten = Integer.parseInt(element.parent().ownText());
                                        AniUtil.sync();
                                        if (totalEpisode) {
                                            ani.setTotalEpisodeNumber(ten);
                                        }
                                    } catch (Exception e) {
                                        if (totalEpisode) {
                                            ani.setTotalEpisodeNumber(totalEpisodeNumber);
                                        }
                                    }

                                }
                                if (totalEpisode) {
                                    ani.setTotalEpisodeNumber(totalEpisodeNumber);
                                }
                            });
                });
    }

    /**
     * 获取番剧在tmdb的名称
     *
     * @param name
     * @return
     */
    public static String getThemoviedbName(String name) {
        try {
            return HttpReq.get("https://www.themoviedb.org/search", true)
                    .form("query", name)
                    .header("accept-language", "zh-CN")
                    .thenFunction(res -> {
                        org.jsoup.nodes.Document document = Jsoup.parse(res.body());
                        Element element = document.selectFirst(".title h2");
                        if (Objects.isNull(element)) {
                            return "";
                        }
                        return StrUtil.blankToDefault(element.ownText(), "");
                    });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return "";
        }
    }

}
