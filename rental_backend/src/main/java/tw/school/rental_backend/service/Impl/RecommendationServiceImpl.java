package tw.school.rental_backend.service.Impl;

import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import tw.school.rental_backend.data.dto.PropertyDTO;
import tw.school.rental_backend.data.dto.PropertyLayoutDTO;
import tw.school.rental_backend.data.dto.ResponseDTO;
import tw.school.rental_backend.model.property.Property;
import tw.school.rental_backend.model.property.PropertyLayout;
import tw.school.rental_backend.model.user.UserAction;
import tw.school.rental_backend.repository.jpa.property.PropertyRepository;
import tw.school.rental_backend.repository.jpa.user.UserActionRepository;
import tw.school.rental_backend.repository.jpa.property.PropertyLayoutRepository;
import tw.school.rental_backend.service.RecommendationService;

import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Service
public class RecommendationServiceImpl implements RecommendationService {

    private final UserActionRepository userActionRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyLayoutRepository propertyLayoutRepository;

    public RecommendationServiceImpl(UserActionRepository actionRepository, PropertyRepository propertyRepository, PropertyLayoutRepository propertyLayoutRepository) {
        this.userActionRepository = actionRepository;
        this.propertyRepository = propertyRepository;
        this.propertyLayoutRepository = propertyLayoutRepository;
    }

    @Override
    public ResponseDTO<List<PropertyDTO>> recommendPropertyForUser(Long userId, Pageable pageable) {
        // 獲取使用者行為記錄
        List<UserAction> userActions = userActionRepository.findByUserId(userId);

        // 如果使用者沒有行為記錄，返回最新的10個房源
        if (userActions.isEmpty()) {
            Page<Property> latestPropertiesPage = propertyRepository.findTop10ByOrderByCreatedAtDesc(pageable);
            List<PropertyDTO> latestPropertyDTOs = latestPropertiesPage.getContent().stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return new ResponseDTO<>(200, latestPropertyDTOs);
        }

        // 使用者的行為記錄，累計推薦分數
        Map<Long, Integer> propertyScore = new HashMap<>();
        Map<String, Integer> propertyTypeCount = new HashMap<>();
        Set<String> viewedDistricts = new HashSet<>();
        Set<String> viewedCities = new HashSet<>();
        int avgPrice = 0;

        // 計算加權分數和統計地區與價格
        for (UserAction actionItem : userActions) {
            Property property = actionItem.getProperty();
            Long propertyId = property.getId();
            String propertyType = property.getPropertyType();

            // 統計每種類型的出現次數
            propertyTypeCount.put(propertyType, propertyTypeCount.getOrDefault(propertyType, 0) + 1);

            viewedDistricts.add(property.getDistrict().getDistrictName());
            viewedCities.add(property.getCity().getCityName());
            avgPrice += property.getPrice();

            // 基於行為類型進行加權
            int score = calculateScoreForAction(actionItem);
            propertyScore.put(propertyId, propertyScore.getOrDefault(propertyId, 0) + score);
        }


        // 計算平均價格，並設置價格範圍
        avgPrice /= userActions.size();
        log.info("Average price: " + avgPrice);

        int priceLowerBound = (int) (avgPrice * 0.8);
        int priceUpperBound = (int) (avgPrice * 1.2);
        log.info("Price range: " + priceLowerBound + " - " + priceUpperBound);

        // 查詢符合條件的房源
        Page<Property> candidatePropertiesPage = propertyRepository.findByCityAndDistrictNamesAndPriceBetween(
                viewedCities, viewedDistricts, priceLowerBound, priceUpperBound, pageable);
        List<Property> candidateProperties = candidatePropertiesPage.getContent();

        // 為符合條件的房源加分
        for (Property property : candidateProperties) {
            Long propertyId = property.getId();
            String propertyType = property.getPropertyType();

            // 基於 propertyType 的加權
            int typeWeight = propertyTypeCount.getOrDefault(propertyType, 0);
            propertyScore.put(propertyId, propertyScore.getOrDefault(propertyId, 0) + typeWeight);

            // 基於價格範圍的加權
            if (property.getPrice() >= priceLowerBound && property.getPrice() <= priceUpperBound) {
                propertyScore.put(propertyId, propertyScore.get(propertyId) + 5);
            }
        }

        // 按加權分數排序並篩選出推薦房源ID
        List<Long> sortedPropertyIds = propertyScore.entrySet().stream()
                .sorted((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        // 查詢推薦的房源
        Page<Property> recommendedPropertiesPage = propertyRepository.findByIdIn(sortedPropertyIds, pageable);
        List<Property> recommendedProperties = recommendedPropertiesPage.getContent();

        // 如果推薦結果不足10個，補充熱門房源
        if (recommendedProperties.size() < 10) {
            List<Property> additionalProperties = propertyRepository
                    .findTop10ByPriceBetweenOrderByCreatedAtDesc(priceLowerBound, priceUpperBound, pageable)
                    .getContent();
            for (Property property : additionalProperties) {
                if (!recommendedProperties.contains(property)) {
                    recommendedProperties.add(property);
                }
            }
        }

        // 將推薦結果轉換成 DTO 並返回
        List<PropertyDTO> propertyDTOs = recommendedProperties.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return new ResponseDTO<>(200, propertyDTOs);
    }

    private int calculateScoreForAction(UserAction action) {
        // 根據行為類型進行加權
        return switch (action.getActionType()) {
            case "VIEW" -> 1;
            case "FAVORITE" -> 5;
            case "CONTACT" -> 10;
            default -> 0;
        };
    }

    public PropertyDTO convertToDTO(Property property) {
        PropertyDTO propertyDTO = new PropertyDTO();
        propertyDTO.setId(property.getId());
        propertyDTO.setTitle(property.getTitle());
        propertyDTO.setCityName(property.getCity().getCityName());
        propertyDTO.setDistrictName(property.getDistrict().getDistrictName());
        propertyDTO.setRoadName(property.getRoad().getRoadName());
        propertyDTO.setAddress(property.getAddress());
        propertyDTO.setPrice(property.getPrice());
        propertyDTO.setPropertyType(property.getPropertyType());
        propertyDTO.setBuildingType(property.getBuildingType());
        propertyDTO.setArea(property.getArea());
        propertyDTO.setFloor(property.getFloor());
        propertyDTO.setStatus(property.getStatus());
        propertyDTO.setMainImage(property.getMainImage());
        propertyDTO.setCreatedAt(property.getCreatedAt());

        // 查詢 PropertyLayout 並設置到 DTO 中
        PropertyLayout propertyLayout = propertyLayoutRepository.findByProperty(property);
        if (propertyLayout != null) {
            PropertyLayoutDTO layoutDTO = new PropertyLayoutDTO();
            layoutDTO.setLivingRoomCount(propertyLayout.getLivingRoomCount());
            layoutDTO.setBathroomCount(propertyLayout.getBathroomCount());
            layoutDTO.setBalconyCount(propertyLayout.getBalconyCount());
            layoutDTO.setKitchenCount(propertyLayout.getKitchenCount());

            propertyDTO.setPropertyLayout(layoutDTO);
        }

        return propertyDTO;
    }
}

