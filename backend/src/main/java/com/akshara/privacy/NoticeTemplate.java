package com.akshara.privacy;

/**
 * The default privacy notice a school starts from, in English and Hindi. Plain text: a line starting with "## " is a
 * heading, a line starting with "- " a list item, anything else a paragraph. Schools edit it before publishing.
 */
final class NoticeTemplate {

    private NoticeTemplate() {
    }

    static String english(String school) {
        return EN.replace("{school}", school);
    }

    static String hindi(String school) {
        return HI.replace("{school}", school);
    }

    private static final String EN = """
            ## Who we are
            {school} ("the school") decides why and how the personal data of students and their parents is used. \
            This notice explains what we collect, why, and the choices and rights you have under the Digital Personal \
            Data Protection Act, 2023.

            ## What we collect
            - About your child: name, date of birth, gender, admission number, class, section and roll number, and, \
            if you give them, blood group, home address, previous school and APAAR ID.
            - About you: name, relation to the child, mobile number, and, if you give them, email address and \
            occupation.
            - Attendance, fee dues, payments and receipts, and the admission application with its tests and \
            interviews.
            We never collect Aadhaar numbers.

            ## Why we use it (essential school records)
            To admit and enrol your child, run classes and attendance, charge fees and issue receipts, keep the \
            records the law requires, and contact you about your child (for example absence alerts and fee messages \
            by SMS or WhatsApp). This is needed to use the parent app.

            ## Optional purposes
            - Photos of your child in school publicity (website, prospectus, social media).
            - School updates on WhatsApp (circulars and events).
            You can say yes or no to each and change your mind at any time in the parent app under Privacy. Saying no \
            does not affect your child's education.

            ## Who sees it
            Only school staff who need it for their work, and service providers who send our messages and run our \
            systems in India under contract. We share data with government bodies and the school board only where \
            the law requires it (for example UDISE+). We never sell personal data.

            ## How long we keep it
            While your child studies here, and afterwards only as long as the law requires: fee receipts and other \
            accounting records for 8 years after the end of the financial year they belong to. Data downloads you ask \
            for are deleted after 7 days.

            ## Your rights
            In the parent app under Privacy you can:
            - ask for a copy of the data we hold about you or your child,
            - ask us to correct or complete it,
            - ask us to erase it once your child has left the school (records the law makes us keep are kept),
            - withdraw optional consent at any time,
            - raise a grievance.
            We reply within 30 days. If you are not satisfied with our answer, you can complain to the Data \
            Protection Board of India.

            ## Contact
            Our grievance officer's name, email and phone number are shown with this notice.""";

    private static final String HI = """
            ## हम कौन हैं
            {school} ("विद्यालय") तय करता है कि विद्यार्थियों और उनके अभिभावकों के व्यक्तिगत डेटा का उपयोग क्यों और \
            कैसे होगा। यह सूचना बताती है कि हम कौन-सी जानकारी लेते हैं, क्यों लेते हैं, और डिजिटल व्यक्तिगत डेटा \
            संरक्षण अधिनियम, 2023 के तहत आपके पास कौन-से विकल्प और अधिकार हैं।

            ## हम कौन-सी जानकारी लेते हैं
            - आपके बच्चे के बारे में: नाम, जन्म तिथि, लिंग, प्रवेश संख्या, कक्षा, सेक्शन और रोल नंबर, और यदि आप दें \
            तो ब्लड ग्रुप, घर का पता, पिछला विद्यालय और APAAR ID।
            - आपके बारे में: नाम, बच्चे से संबंध, मोबाइल नंबर, और यदि आप दें तो ईमेल पता और व्यवसाय।
            - उपस्थिति, शुल्क बकाया, भुगतान और रसीदें, और प्रवेश आवेदन के साथ उसकी परीक्षाएँ और साक्षात्कार।
            हम कभी भी आधार नंबर नहीं लेते।

            ## हम इसका उपयोग क्यों करते हैं (आवश्यक विद्यालय रिकॉर्ड)
            आपके बच्चे का प्रवेश और नामांकन करने, कक्षाएँ और उपस्थिति चलाने, शुल्क लेने और रसीद देने, कानून द्वारा \
            आवश्यक रिकॉर्ड रखने, और आपके बच्चे के बारे में आपसे संपर्क करने के लिए (जैसे SMS या WhatsApp पर \
            अनुपस्थिति और शुल्क के संदेश)। अभिभावक ऐप का उपयोग करने के लिए यह आवश्यक है।

            ## वैकल्पिक उद्देश्य
            - विद्यालय के प्रचार (वेबसाइट, प्रॉस्पेक्टस, सोशल मीडिया) में आपके बच्चे की तस्वीरें।
            - WhatsApp पर विद्यालय की सूचनाएँ (परिपत्र और कार्यक्रम)।
            आप हर एक के लिए हाँ या नहीं कह सकते हैं और अभिभावक ऐप में "गोपनीयता" में कभी भी अपना निर्णय बदल सकते \
            हैं। नहीं कहने से आपके बच्चे की पढ़ाई पर कोई असर नहीं पड़ता।

            ## इसे कौन देखता है
            केवल विद्यालय के वे कर्मचारी जिन्हें अपने काम के लिए इसकी ज़रूरत है, और वे सेवा प्रदाता जो अनुबंध के \
            तहत भारत में हमारे संदेश भेजते हैं और हमारी प्रणालियाँ चलाते हैं। हम सरकारी संस्थाओं और बोर्ड के साथ \
            डेटा केवल तभी साझा करते हैं जब कानून ऐसा कहता है (जैसे UDISE+)। हम व्यक्तिगत डेटा कभी नहीं बेचते।

            ## हम इसे कितने समय तक रखते हैं
            जब तक आपका बच्चा यहाँ पढ़ता है, और उसके बाद केवल उतने समय तक जितना कानून कहता है: शुल्क रसीदें और \
            अन्य लेखा रिकॉर्ड संबंधित वित्तीय वर्ष की समाप्ति के बाद 8 वर्ष तक। आपके माँगे गए डेटा डाउनलोड 7 दिन \
            बाद हटा दिए जाते हैं।

            ## आपके अधिकार
            अभिभावक ऐप में "गोपनीयता" में आप:
            - हमारे पास आपके या आपके बच्चे के बारे में रखे डेटा की प्रति माँग सकते हैं,
            - उसे सुधारने या पूरा करने के लिए कह सकते हैं,
            - बच्चे के विद्यालय छोड़ने के बाद उसे मिटाने के लिए कह सकते हैं (जो रिकॉर्ड कानून के अनुसार रखने \
            ज़रूरी हैं, वे रखे जाते हैं),
            - वैकल्पिक सहमति कभी भी वापस ले सकते हैं,
            - शिकायत दर्ज कर सकते हैं।
            हम 30 दिनों के भीतर उत्तर देते हैं। यदि आप हमारे उत्तर से संतुष्ट नहीं हैं, तो आप भारतीय डेटा संरक्षण \
            बोर्ड से शिकायत कर सकते हैं।

            ## संपर्क
            हमारे शिकायत अधिकारी का नाम, ईमेल और फ़ोन नंबर इस सूचना के साथ दिखाया गया है।""";
}
