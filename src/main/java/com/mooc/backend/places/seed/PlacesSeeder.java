package com.mooc.backend.places.seed;

import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.domain.CitySlugs;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 城市与景点种子导入（{@code city-module} 种子 Requirement）。
 *
 * <p>仅在 {@code seed} profile 下注册（{@code @Profile("seed")}），常规启动 / 测试不加载。
 * 幂等语义：城市与景点各自按（自动生成的）{@code slug} 判重，已存在（含软删行）即跳过；
 * 因此重复执行不产生重复行、无唯一键冲突。城市 slug 一律由 {@code name} 经
 * {@link CitySlugs#slugify} 生成，种子数据源不手工提供 slug；景点 slug 为 {@code {citySlug}-{spotSlug}} 复合。
 *
 * <p>封面图使用 picsum.photos 占位（seed 参数保证同一实体 URL 稳定），后续换真实图片只改 URL 不改结构。
 */
@Component
@Profile("seed")
public class PlacesSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlacesSeeder.class);

    private final CityRepository cityRepository;
    private final SpotRepository spotRepository;

    public PlacesSeeder(CityRepository cityRepository, SpotRepository spotRepository) {
        this.cityRepository = cityRepository;
        this.spotRepository = spotRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 幂等导入全部种子（城市 + 景点）。 */
    @Transactional
    public void seed() {
        for (CitySeed city : CITIES) {
            seedCity(city);
        }
        log.info("Places seeding done: {} cities defined.", CITIES.size());
    }

    private void seedCity(CitySeed city) {
        String citySlug = CitySlugs.slugify(city.name());
        if (cityRepository.findBySlug(citySlug).isEmpty()) {
            City saved = cityRepository.saveAndFlush(City.create(UUID.randomUUID(), city.name(), city.nameZh(),
                    citySlug, coverImage(citySlug), city.description(), city.bestSeason(), Instant.now()));
            log.info("Seeded city {}", saved.getSlug());
        }
        for (SpotSeed spot : city.spots()) {
            seedSpot(citySlug, spot);
        }
    }

    private void seedSpot(String citySlug, SpotSeed spot) {
        String spotSlug = citySlug + "-" + CitySlugs.slugify(spot.nameEn());
        if (spotRepository.findBySlug(spotSlug).isPresent()) {
            return;
        }
        spotRepository.saveAndFlush(Spot.create(UUID.randomUUID(), spotSlug, spot.nameZh(), spot.nameEn(),
                citySlug, spot.category(), spot.tags(), null, null, null, null, null,
                coverImage(spotSlug), List.of(coverImage(spotSlug + "-gallery")),
                spot.summaryEn(), spot.summaryZh(), spot.descriptionEn(), spot.descriptionZh(),
                null, null, null, spot.rating(), spot.featured(), spot.hiddenGem(),
                SpotStatus.PUBLISHED, Instant.now()));
        log.info("Seeded spot {}", spotSlug);
    }

    private static String coverImage(String seed) {
        return "https://picsum.photos/seed/" + seed + "/1200/800";
    }

    private record CitySeed(String name, String nameZh, String description, String bestSeason,
                            List<SpotSeed> spots) {
    }

    /**
     * 种子景点源数据。
     *
     * <p>{@code tags} / {@code rating} / {@code featured} / {@code hiddenGem} 是列表卡片与
     * 首页精选槽位的可展示字段，缺失会让这些 UI 无内容可渲染（标签筛选器还会因
     * {@code JSON_CONTAINS} 永不命中而彻底失效），故种子必须填充。
     *
     * <p>{@code viewCount} 不在此列：热度属运行时累积与爬虫职责，种子期编造热度会掩盖
     * 「真实热度尚未接入」这一事实；排序正确性由 SQL 的 slug tie-breaker 保证。
     *
     * <p>标签词须跨景点复用（每个词至少 2 条景点使用），否则标签筛选器会退化成搜索框。
     */
    private record SpotSeed(String nameZh, String nameEn, SpotCategory category,
                            List<String> tags, Double rating, boolean featured, boolean hiddenGem,
                            String summaryEn, String summaryZh,
                            String descriptionEn, String descriptionZh) {
    }

    private static final List<CitySeed> CITIES = List.of(
            new CitySeed("Beijing", "北京",
                    "China's capital blends imperial grandeur with modern energy — the Forbidden City and the Great Wall sit alongside hutongs and contemporary art districts.",
                    "Sep–Oct · Apr–May",
                    List.of(
                            new SpotSeed("故宫", "Forbidden City", SpotCategory.HISTORY,
                                    List.of("UNESCO", "Must-see", "Museum"), 4.9, true, false,
                                    "The imperial palace of the Ming and Qing dynasties, a vast complex of halls and courtyards at the heart of Beijing.",
                                    "明清两代的皇宫，由无数殿宇与庭院构成的庞大建筑群，坐镇北京中轴线的中心。",
                                    "Walk north from Tiananmen through five ceremonial gates and ninety-something halls to feel the scale of imperial China. Allow at least half a day, and book tickets in advance.",
                                    "从天安门向北穿过五重宫门与近百座殿宇，方能感受帝制中国的规模。建议预留至少半天，并提前预约门票。"),
                            new SpotSeed("慕田峪长城", "Great Wall at Mutianyu", SpotCategory.NATURE,
                                    List.of("UNESCO", "Must-see", "Hiking", "Cable Car"), 4.8, true, false,
                                    "A beautifully restored section of the Great Wall snaking over forested ridges north of the city.",
                                    "长城保存最完好、风景最秀美的段落之一，沿城北苍翠山脊蜿蜒起伏。",
                                    "Cable cars, a chairlift and a toboggan run make Mutianyu the most family-friendly Great Wall section — far less crowded than Badaling.",
                                    "缆车、索道与滑道一应俱全，是长城各段中亲子体验最友好的一处，客流也远少于八达岭。"),
                            new SpotSeed("天坛", "Temple of Heaven", SpotCategory.HISTORY,
                                    List.of("UNESCO", "Temple", "Photo"), 4.6, false, true,
                                    "Where Ming and Qing emperors prayed for good harvests — famous for its circular Hall of Prayer.",
                                    "明清帝王祭天祈谷之所，以圆形祈年殿闻名于世。",
                                    "The park is a local morning ritual too: you will meet tai chi groups, singers and dancers in the cypress groves.",
                                    "天坛公园也是老北京晨练的舞台，柏树林里常有太极、合唱与舞蹈人群。"))),
            new CitySeed("Shanghai", "上海",
                    "A futuristic skyline over the Huangpu River meets tree-lined former French Concession streets in China's most cosmopolitan city.",
                    "Mar–May · Sep–Nov",
                    List.of(
                            new SpotSeed("外滩", "The Bund", SpotCategory.DISTRICT,
                                    List.of("Must-see", "Night View", "Photo"), 4.7, true, false,
                                    "Shanghai's riverside promenade lined with grand colonial buildings, facing the Pudong towers across the Huangpu.",
                                    "黄浦江畔的滨江大道，一侧是恢弘的万国建筑博览群，隔江正对陆家嘴摩天楼群。",
                                    "Best at dusk: the colonial facades light up as Pudong's skyline flickers on opposite the river.",
                                    "黄昏时分最佳——万国建筑亮起灯光，对岸陆家嘴的天际线随之点亮。"),
                            new SpotSeed("豫园", "Yu Garden", SpotCategory.HISTORY,
                                    List.of("Old Town", "Photo", "Food"), 4.4, false, true,
                                    "A Ming-dynasty classical garden of rockeries, ponds and pavilions hidden behind the old city bazaar.",
                                    "藏身老城厢市集深处的明代古典园林，以假山、池沼与亭台取胜。",
                                    "After the garden, wander the surrounding Yu Garden Bazaar for snacks like xiaolongbao and tanghulu.",
                                    "游园后可逛毗邻的豫园商城，尝一尝小笼包、糖葫芦等地道小吃。"),
                            new SpotSeed("上海博物馆东馆", "Shanghai Museum East", SpotCategory.CULTURE,
                                    List.of("Museum", "Free", "Must-see"), 4.6, false, false,
                                    "A new landmark museum on the Pudong waterfront showcasing China's finest bronzes and calligraphy.",
                                    "坐落浦东滨江的新地标博物馆，以顶级青铜器与中国书画收藏著称。",
                                    "Free timed-entry galleries make it easy to dip into 5,000 years of Chinese art between skyline walks.",
                                    "免费预约入馆，看展之余沿滨江步道赏天际线，动静皆宜。"))),
            new CitySeed("Xi'an", "西安",
                    "Capital of thirteen dynasties and eastern terminus of the Silk Road — home to the Terracotta Army and China's best-preserved city wall.",
                    "Mar–May · Sep–Oct",
                    List.of(
                            new SpotSeed("兵马俑", "Terracotta Army", SpotCategory.HISTORY,
                                    List.of("UNESCO", "Must-see", "Museum"), 4.9, true, false,
                                    "Thousands of life-size clay soldiers guard the tomb of the First Emperor, discovered in 1974.",
                                    "1974 年出土的秦始皇陵陪葬坑，数千个真人大小的陶俑军阵气势撼人。",
                                    "Visit Pit 1 first for the famous rows of warriors, then the smaller pits for kneeling archers and bronze chariots.",
                                    "先看一号坑的庞大军阵，再走二号、三号坑欣赏跪射俑与铜车马。"),
                            new SpotSeed("西安城墙", "Xi'an City Wall", SpotCategory.HISTORY,
                                    List.of("Must-see", "Photo", "Hiking"), 4.6, false, false,
                                    "The best-preserved ancient city wall in China — cycle the full 14 km circuit above the old town.",
                                    "中国保存最完整的古城墙，全长约 14 公里，可骑行环游。",
                                    "Rent a bike at the South Gate and ride the entire loop in about two hours, looking down over the old city.",
                                    "在南门租一辆单车，两小时左右即可骑完一圈，俯瞰整座古城。"))),
            new CitySeed("Chengdu", "成都",
                    "Home of giant pandas, spicy hotpot and a famously unhurried teahouse culture in Sichuan's fertile basin.",
                    "Mar–Jun · Sep–Nov",
                    List.of(
                            new SpotSeed("大熊猫繁育研究基地", "Chengdu Panda Base", SpotCategory.NATURE,
                                    List.of("Family", "Must-see", "Photo"), 4.8, true, false,
                                    "The best place on Earth to see giant pandas — cubs, bamboo-munching adults and red pandas in leafy enclosures.",
                                    "全球观赏大熊猫的最佳去处，茂密竹林中栖息着幼崽、成年熊猫与小熊猫。",
                                    "Arrive at opening time to catch the pandas at their most active, then watch cubs climb trees in the nursery.",
                                    "建议开门即入园，趁熊猫最精神的时候看它们进食，再到产房看幼崽爬树。"),
                            new SpotSeed("武侯祠", "Wuhou Shrine", SpotCategory.HISTORY,
                                    List.of("Temple", "Old Town", "Food"), 4.5, false, true,
                                    "A serene temple-museum honouring the strategist Zhuge Liang, set beside the lively Jinli bazaar.",
                                    "纪念诸葛亮的名胜，殿宇肃穆，毗邻热闹的锦里古街。",
                                    "Combine the shrine with the adjacent Jinli Old Street for Sichuan snacks and folk crafts.",
                                    "可把武侯祠与一墙之隔的锦里老街连成一线，吃川味小吃、逛民间手艺。"))),
            new CitySeed("Hangzhou", "杭州",
                    "An ancient lakeside capital whose West Lake inspired poets for a thousand years — gardens, temples and tea hills.",
                    "Mar–May · Sep–Nov",
                    List.of(
                            new SpotSeed("西湖", "West Lake", SpotCategory.NATURE,
                                    List.of("UNESCO", "Must-see", "Free", "Photo"), 4.8, true, false,
                                    "A UNESCO-listed lake of causeways, pagodas and willows that has inspired poets for over a millennium.",
                                    "世界文化遗产，苏堤白堤、亭台佛塔与垂柳相映，千年来一直是诗画江南的代名词。",
                                    "Rent a shared bike for the Su Causeway, take a boat to the island of Three Pools, then watch the sunset by Leifeng Pagoda.",
                                    "可租共享单车骑行苏堤，乘船登三潭印月小岛，再到雷峰塔边看日落。"),
                            new SpotSeed("灵隐寺", "Lingyin Temple", SpotCategory.HISTORY,
                                    List.of("Temple", "Hiking", "Photo"), 4.7, false, true,
                                    "One of China's great Buddhist monasteries, tucked into wooded hills west of West Lake.",
                                    "中国著名古刹之一，深藏于西湖以西的密林群山之间。",
                                    "Arrive early to explore the mossy grottoes of Feilai Feng before the tour groups fill the courtyards.",
                                    "建议赶早，赶在旅行团涌入前细看飞来峰的摩崖石刻。"))),
            new CitySeed("Guilin", "桂林",
                    "Karst peaks rising from the Li River make this the postcard image of southern China's landscape.",
                    "Apr–Oct",
                    List.of(
                            new SpotSeed("漓江", "Li River", SpotCategory.NATURE,
                                    List.of("UNESCO", "Must-see", "Photo"), 4.7, true, false,
                                    "The classic karst cruise from Guilin to Yangshuo — sheer limestone peaks mirrored in calm green water.",
                                    "从桂林到阳朔的经典喀斯特水路，石灰岩峰林倒映在碧绿江水中。",
                                    "Take the four-hour bamboo-raft or cruise option from Zhujiang Pier, passing the scenes printed on the ¥20 note.",
                                    "可选择竹筏或游船，从竹江码头出发的四个多小时航程会经过印在二十元人民币背面的风景。"),
                            new SpotSeed("芦笛岩", "Reed Flute Cave", SpotCategory.NATURE,
                                    List.of("Photo", "Family"), 4.3, false, true,
                                    "A limestone cave of glittering stalactites and stalagmites, lit in rainbow colours.",
                                    "遍布钟乳石与石笋的溶洞，在彩色灯光下晶莹剔透，被誉为「大自然的艺术之宫」。",
                                    "The forty-minute guided walk is conveniently close to the city centre and shows off the cave's best-lit chambers.",
                                    "溶洞位于市中心不远，讲解游览约四十分钟，尽览最精美的石室。"))),
            new CitySeed("Lhasa", "拉萨",
                    "A high-altitude pilgrimage city where the Potala Palace crowns the sky above whitewashed monasteries and prayer flags.",
                    "May–Oct",
                    List.of(
                            new SpotSeed("布达拉宫", "Potala Palace", SpotCategory.HISTORY,
                                    List.of("UNESCO", "Must-see", "Museum", "Photo"), 4.8, true, false,
                                    "The iconic thirteen-storey winter palace of the Dalai Lamas, rising red and white above Lhasa.",
                                    "达赖喇嘛的冬宫，红白相间的十三层宫堡雄踞红山之巅，是拉萨的永恒地标。",
                                    "Acclimatise for a day before climbing the hundreds of steps; book tickets early as daily numbers are capped.",
                                    "初到高原先适应一天再登数百级台阶；旺季门票数量有限，务必提前预订。"),
                            new SpotSeed("大昭寺", "Jokhang Temple", SpotCategory.CULTURE,
                                    List.of("UNESCO", "Temple", "Photo"), 4.6, false, true,
                                    "Tibet's holiest shrine, whose rooftop views over Barkhor pilgrims make it Lhasa's spiritual heart.",
                                    "藏传佛教最神圣的殿堂，登顶可望见八廓街上转经的人潮，是拉萨的精神中心。",
                                    "Join the clockwise kora around the Barkhor circuit after visiting the temple's golden-roofed chapels.",
                                    "礼佛后沿八廓街顺时针转经一圈，感受朝圣者的日常。"))),
            new CitySeed("Lijiang", "丽江",
                    "A cobblestoned Naxi old town under snowy Jade Dragon Mountain, threaded with canals and wooden inns.",
                    "Mar–May · Sep–Nov",
                    List.of(
                            new SpotSeed("丽江古城", "Lijiang Old Town", SpotCategory.HISTORY,
                                    List.of("UNESCO", "Old Town", "Night View", "Photo"), 4.6, false, false,
                                    "A UNESCO-listed maze of cobbled lanes, canals and Naxi timber houses beneath Jade Dragon Mountain.",
                                    "世界文化遗产，玉龙雪山下的纳西木构老城，青石板路与水渠纵横。",
                                    "Wander off Sifang Street after dark, when lantern-lit lanes are quieter and the mountain looms at the lane's end.",
                                    "入夜后离开四方街主路，灯笼照亮的巷弄格外清静，尽头是巍峨的雪山。"),
                            new SpotSeed("玉龙雪山", "Jade Dragon Snow Mountain", SpotCategory.NATURE,
                                    List.of("Cable Car", "Hiking", "Photo"), 4.5, false, false,
                                    "A glacier-topped range of thirteen peaks, reached by cable car to meadows above 4,000 m.",
                                    "十三峰连绵的雪山，可乘索道登上海拔四千多米的草甸与冰川。",
                                    "Book the big cable car ahead and carry oxygen — the summit walkway sits above 4,500 metres.",
                                    "大索道需提前预约并自备氧气瓶，山顶栈道海拔超过 4500 米。"))),
            new CitySeed("Guangzhou", "广州",
                    "Cantonese food capital and southern trading hub, where old arcaded streets meet the Pearl River's modern skyline.",
                    "Oct–Dec · Feb–Apr",
                    List.of(
                            new SpotSeed("广州塔", "Canton Tower", SpotCategory.DISTRICT,
                                    List.of("Night View", "Photo", "Must-see"), 4.5, false, false,
                                    "The twisted 600-metre TV tower dominating the Pearl River skyline — best viewed lit up at night.",
                                    "珠江畔扭腰造型的 600 米高电视塔，入夜后灯光绚烂，是广州的城市名片。",
                                    "Take the metro to Zhujiang New Town across the river for the classic skyline photo, then cross the bridge for a river cruise.",
                                    "可乘地铁到对岸珠江新城拍经典机位，再过桥登船夜游珠江。"),
                            new SpotSeed("陈家祠", "Chen Clan Ancestral Hall", SpotCategory.CULTURE,
                                    List.of("Museum", "Photo", "Old Town"), 4.4, false, false,
                                    "A dazzling Qing-dynasty clan hall covered in ridge figurines, carvings and painted ceramics.",
                                    "清代宗祠建筑的登峰造极之作，屋脊陶塑、木雕与彩绘琳琅满目。",
                                    "One of the finest examples of Lingnan decorative art — allow an hour and look up at the rooftop figures.",
                                    "岭南装饰艺术的集大成者——留一小时细品，别忘了抬头看屋脊上的陶塑。"))),
            new CitySeed("Chongqing", "重庆",
                    "A vertiginous mountain city where monorails thread between skyscrapers and the Yangtze meets the Jialing.",
                    "Mar–May · Sep–Nov",
                    List.of(
                            new SpotSeed("洪崖洞", "Hongya Cave", SpotCategory.DISTRICT,
                                    List.of("Night View", "Food", "Photo"), 4.4, false, false,
                                    "A cliff-side complex of stilted traditional buildings that glows like a floating lantern city at night.",
                                    "依山而建的吊脚楼群，入夜灯火如悬空的宫崎骏世界，是重庆最上镜的夜景。",
                                    "View it from the riverside road below for the full effect, then ride the nearby Liziba monorail that passes through a residential tower.",
                                    "最佳机位在江岸步道仰拍；随后可乘穿楼而过的李子坝轻轨。"),
                            new SpotSeed("磁器口古镇", "Ciqikou Ancient Town", SpotCategory.HISTORY,
                                    List.of("Old Town", "Food", "Photo"), 4.2, false, true,
                                    "A restored Ming-Qing riverside town of teahouses, calligraphy shops and chilli-laden snack stalls.",
                                    "嘉陵江畔修复的明清古镇，茶馆、书法铺与辣味小吃摊沿石板路铺开。",
                                    "Come for the morning teahouse scene and fresh maohuaxuewang hotpot, and climb to the old wharf for river views.",
                                    "早茶时段最有味道，试试现做毛血旺，再登上老码头看江景。"))),
            new CitySeed("Fuzhou", "福州",
                    "A subtropical provincial capital of banyan trees, hot springs and old lane quarters — and the other West Lake.",
                    "Oct–Apr",
                    List.of(
                            new SpotSeed("福州西湖", "West Lake", SpotCategory.NATURE,
                                    List.of("Free", "Photo", "Family"), 4.1, false, true,
                                    "A compact lakeside park in the city centre, quieter and more local than its famous Hangzhou namesake.",
                                    "市中心的小巧湖景公园，比杭州同名西湖更小、更安静，也更有本地生活气息。",
                                    "Fuzhou's West Lake is a neighbourhood retreat where locals stroll, practise tai chi and boat under willow trees — a useful reminder that West Lake is a name, not a single place.",
                                    "福州西湖是街坊邻里的休憩之地，本地人在此散步、打太极、垂柳下泛舟——它也提醒着旅行者：西湖是一个名字，而非独指一处。"),
                            new SpotSeed("三坊七巷", "Three Lanes and Seven Alleys", SpotCategory.HISTORY,
                                    List.of("Old Town", "Food", "Photo"), 4.3, false, false,
                                    "A preserved Ming-Qing quarter of narrow lanes and courtyard houses, once home to scholars and officials.",
                                    "保存完好的明清坊巷街区，窄巷深宅，曾是文人官宦的聚居之地。",
                                    "Three Lanes and Seven Alleys is the best-surviving old quarter in Fuzhou: restored courtyard mansions, bookshops and snack stalls woven into a walkable grid.",
                                    "三坊七巷是福州保存最完整的老城区：修葺一新的深宅大院、书店与小吃摊交织在可步行的坊巷格局中。"))));
}
